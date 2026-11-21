#!/usr/bin/env python3
"""Builds the shipped catalog of Google Play categories for popular apps.

Reads only public Google Play pages that play.google.com/robots.txt allows
(category, app and developer pages; never search), one at a time with a gap
between them, and backs off when asked to slow down. Starts from the category
pages and the seed packages given, follows each app page's "similar apps" and
developer links, and keeps apps with at least --min-installs downloads.

State is saved as it goes, so a stopped run resumes where it left off:

    scripts/play-catalog/crawl.py --state STATE_DIR --target 50000 --hours 5
    scripts/play-catalog/crawl.py --state STATE_DIR --write app/src/main/assets/play_catalog.tsv

Logs go to stderr and ~/.local/state/typelauncher/play-catalog.log.
"""

import argparse
import heapq
import json
import logging
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PLAY = "https://play.google.com"
USER_AGENT = "Mozilla/5.0 (compatible; typelauncher-catalog)"
CATEGORIES = [
    "ART_AND_DESIGN", "AUTO_AND_VEHICLES", "BEAUTY", "BOOKS_AND_REFERENCE", "BUSINESS",
    "COMICS", "COMMUNICATION", "DATING", "EDUCATION", "ENTERTAINMENT", "EVENTS", "FINANCE",
    "FOOD_AND_DRINK", "HEALTH_AND_FITNESS", "HOUSE_AND_HOME", "MAPS_AND_NAVIGATION",
    "MEDICAL", "MUSIC_AND_AUDIO", "NEWS_AND_MAGAZINES", "PARENTING", "PERSONALIZATION",
    "PHOTOGRAPHY", "PRODUCTIVITY", "SHOPPING", "SOCIAL", "SPORTS", "TOOLS",
    "TRAVEL_AND_LOCAL", "VIDEO_PLAYERS", "WEATHER", "GAME",
]
APP_LINK = re.compile(r"details\?id=([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)")
# A developer page link, endpoint and id as the page has them (still
# URL-encoded): "/store/apps/dev?id=123" or "/store/apps/developer?id=Example+Studio".
DEV_LINK = re.compile(r"/store/apps/(dev|developer)\?id(?:=|\\u003d)([^\"&\\]+)")
CATEGORY = re.compile(r'"applicationCategory"\s*:\s*"([A-Z_]+)"')
# The app's own download count, as the page's data has it: "10,000,000+",10000000,
INSTALLS = re.compile(r'"[0-9,]+\+",([0-9]+),[0-9]+')


# Times an app page may fail (no network, a timeout, a server error) before
# it's left out.
MAX_ATTEMPTS = 3


class SlowDown(Exception):
    pass


def fetch(path):
    """The page at [path], or None when Google Play has none."""
    request = urllib.request.Request(PLAY + path, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        if error.code in (429, 503):
            raise SlowDown() from error
        raise


class Crawl:
    def __init__(self, state_dir, min_installs):
        self.state_dir = state_dir
        self.min_installs = min_installs
        self.apps = {}  # package -> [category, installs]
        self.seen = set()  # packages queued or fetched
        self.queue = []  # (-priority, package)
        self.devs_done = set()
        self.pages_done = set()
        self.failures = {}  # package -> failed attempts
        self.spacing = 0
        self.last_request = None
        self.load()

    def fetch(self, path):
        """[fetch], at least [spacing] seconds after the previous request,
        whichever page it was for (Codex on PR #889)."""
        if self.last_request is not None:
            wait = self.last_request + self.spacing - time.monotonic()
            if wait > 0:
                time.sleep(wait)
        try:
            return fetch(path)
        finally:
            self.last_request = time.monotonic()

    def path(self, name):
        return os.path.join(self.state_dir, name)

    def load(self):
        os.makedirs(self.state_dir, exist_ok=True)
        try:
            with open(self.path("state.json")) as f:
                state = json.load(f)
        except FileNotFoundError:
            return
        self.apps = state["apps"]
        self.seen = set(state["seen"])
        self.queue = [tuple(item) for item in state["queue"]]
        heapq.heapify(self.queue)
        self.devs_done = set(state["devs_done"])
        self.pages_done = set(state["pages_done"])
        self.failures = state.get("failures", {})
        logging.info("resumed: %d apps, %d queued", len(self.apps), len(self.queue))

    def save(self):
        state = {
            "apps": self.apps,
            "seen": sorted(self.seen),
            "queue": self.queue,
            "devs_done": sorted(self.devs_done),
            "pages_done": sorted(self.pages_done),
            "failures": self.failures,
        }
        temp = self.path("state.json.tmp")
        with open(temp, "w") as f:
            json.dump(state, f)
        os.replace(temp, self.path("state.json"))

    def enqueue(self, package, priority):
        if package not in self.seen:
            self.seen.add(package)
            heapq.heappush(self.queue, (-priority, package))

    def links(self, page, priority):
        for package in APP_LINK.findall(page):
            self.enqueue(package, priority)

    def crawl_page(self, key, path, priority):
        if key in self.pages_done:
            return
        page = self.fetch(path)
        self.pages_done.add(key)
        if page:
            self.links(page, priority)

    def crawl_app(self, package):
        page = self.fetch("/store/apps/details?id=" + urllib.parse.quote(package) + "&hl=en")
        if page is None:
            return
        category = CATEGORY.search(page)
        installs = INSTALLS.search(page)
        count = int(installs.group(1)) if installs else 0
        if category and count >= self.min_installs:
            self.apps[package] = [category.group(1), count]
        # Popular apps' neighbors are worth more; never follow from obscure ones.
        if count >= self.min_installs:
            self.links(page, count)
            for endpoint, dev_id in set(DEV_LINK.findall(page)):
                # Requested as linked, encoding and all (Codex on PR #889).
                dev = "/store/apps/" + endpoint + "?id=" + dev_id
                if dev not in self.devs_done:
                    dev_page = self.fetch(dev + "&hl=en")
                    # Done only once fetched: a slow-down retries the app, and
                    # with it this page (Codex on PR #889).
                    self.devs_done.add(dev)
                    if dev_page:
                        self.links(dev_page, count // 2)

    def run(self, seeds, target, spacing, deadline=None):
        """Crawls until [target] apps are kept, the queue empties, or the
        monotonic clock passes [deadline]."""
        self.spacing = spacing
        for package in seeds:
            self.enqueue(package, 10**12)
        for category in CATEGORIES:
            try:
                self.crawl_page("category:" + category,
                                "/store/apps/category/" + category + "?hl=en", 10**11)
            except SlowDown:
                logging.warning("slow down on category %s; waiting", category)
                time.sleep(600)
            except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                # Not marked done, so the next run tries it again; the seeds
                # and the other categories carry on (Codex on PR #889).
                logging.warning("failed category %s: %s", category, error)
        fetched = 0
        while self.queue and len(self.apps) < target:
            if deadline is not None and time.monotonic() >= deadline:
                logging.info("out of time")
                break
            priority, package = heapq.heappop(self.queue)
            try:
                self.crawl_app(package)
            except KeyboardInterrupt:
                # Back in the queue, so the saved state still has it to fetch
                # (Codex on PR #889).
                heapq.heappush(self.queue, (priority, package))
                raise
            except SlowDown:
                logging.warning("asked to slow down after %d pages; waiting 10 minutes", fetched)
                heapq.heappush(self.queue, (-10**12, package))
                self.save()
                time.sleep(600)
                continue
            except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                # One failed page shouldn't end a run of hours: queue it again,
                # behind what's waiting, up to MAX_ATTEMPTS (Codex on PR #889).
                attempts = self.failures.get(package, 0) + 1
                self.failures[package] = attempts
                if attempts < MAX_ATTEMPTS:
                    logging.warning("failed %s (attempt %d), will retry: %s", package, attempts, error)
                    heapq.heappush(self.queue, (priority // 2, package))
                else:
                    logging.warning("failed %s %d times, giving up: %s", package, attempts, error)
            fetched += 1
            if fetched % 100 == 0:
                self.save()
                logging.info("%d pages, %d apps, %d queued", fetched, len(self.apps), len(self.queue))
        self.save()
        logging.info("stopped: %d apps, %d queued", len(self.apps), len(self.queue))

    def write(self, out):
        rows = sorted(self.apps.items())
        with open(out, "w") as f:
            f.write("# Google Play category by package, from scripts/play-catalog/crawl.py.\n")
            for package, (category, _) in rows:
                f.write(package + "\t" + category + "\n")
        logging.info("wrote %d apps to %s", len(rows), out)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--state", required=True, help="directory for the resumable state")
    parser.add_argument("--seeds", action="append", default=[], help="file of seed packages, one a line")
    parser.add_argument("--target", type=int, default=50000, help="stop once this many apps are kept")
    parser.add_argument("--min-installs", type=int, default=100000)
    parser.add_argument("--spacing", type=float, default=2.0, help="seconds between pages")
    parser.add_argument("--hours", type=float, help="stop after this long, state saved, to resume later")
    parser.add_argument("--write", help="write the catalog here instead of crawling")
    args = parser.parse_args()

    log_dir = os.path.expanduser("~/.local/state/typelauncher")
    os.makedirs(log_dir, exist_ok=True)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[logging.StreamHandler(), logging.FileHandler(os.path.join(log_dir, "play-catalog.log"))],
    )
    logging.info("started: %s", " ".join(sys.argv[1:]))
    crawl = Crawl(args.state, args.min_installs)
    try:
        if args.write:
            crawl.write(args.write)
        else:
            seeds = []
            for name in args.seeds:
                with open(name) as f:
                    seeds += [line.split("#")[0].strip() for line in f if line.split("#")[0].strip()]
            deadline = time.monotonic() + args.hours * 3600 if args.hours else None
            crawl.run(seeds, args.target, args.spacing, deadline)
    except KeyboardInterrupt:
        crawl.save()
        logging.info("interrupted; state saved")
        return 130
    except Exception:
        logging.exception("failed")
        crawl.save()
        return 1
    logging.info("ended")
    return 0


if __name__ == "__main__":
    sys.exit(main())
