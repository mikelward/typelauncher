#!/usr/bin/env python3
"""Builds the shipped catalog of Google Play categories for popular apps.

Reads only public Google Play pages that play.google.com/robots.txt allows
(category, app and developer pages; never search), one at a time with a gap
between them, and backs off when asked to slow down. Starts from the category
pages and the seed packages given, follows each app page's "similar apps" and
developer links, and keeps apps with at least --min-installs downloads, or
--local-min-installs for the seeds and their developers' other apps.

With --countries, each category page is read in each country's Google Play,
and an app found there is read, and its links followed, in that country's, so
apps sold in only one country are found (maintainer, 2026-11-24: Costa, Pret,
Gumtree and Amex UK were never found from the runner's US view). A seed is
read in the first country that has it. Apps already shipped are read only
once nothing else is left, for the neighbors they lead to.

State is saved as it goes, so a stopped run resumes where it left off:

    scripts/play-catalog/crawl.py --state STATE_DIR --target 50000 --hours 5
    scripts/play-catalog/crawl.py --state STATE_DIR --write app/src/main/assets/play_catalog.tsv

Logs go to stderr and ~/.local/state/typelauncher/play-catalog.log.
"""

import argparse
import http.client
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
# The id ends at whitespace too: a link quoted in an app's description runs
# into the sentence after it ("dev?id=123 . We are glad...").
DEV_LINK = re.compile(r"/store/apps/(dev|developer)\?id(?:=|\\u003d)([^\"&\\\s<>]+)")
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


def with_country(path, country):
    """[path], read in [country]'s Google Play when one is given."""
    return path + "&gl=" + country if country else path


class Crawl:
    def __init__(self, state_dir, min_installs, local_min_installs=None, countries=None):
        self.state_dir = state_dir
        # Countries whose Google Play is read; "" is the runner's own, as before.
        self.countries = list(countries) if countries else [""]
        self.min_installs = min_installs
        # Seeds and their developers' other apps: a country's own apps, kept
        # from a lower bar, since its downloads are one country's (maintainer,
        # 2026-11-21: a Croatian bank has far fewer than a global app).
        self.local_min_installs = min_installs if local_min_installs is None else local_min_installs
        self.local = set()
        # Developer pages fetched for a seed's sake, whose apps are local too.
        self.devs_done_local = set()
        self.apps = {}  # package -> [category, installs]
        self.seen = set()  # packages queued or fetched
        self.queue = []  # (-priority, package, country)
        # Shipped apps queued once the queue ran dry, so none is read twice.
        self.shipped_read = set()
        # Seeds given another try in each country.
        self.seeds_retried = set()
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
        # Games kept before the crawl skipped them don't count towards the
        # target (Codex on PR #908).
        self.apps = {p: v for p, v in state["apps"].items() if not v[0].startswith("GAME")}
        self.seen = set(state["seen"])
        # State saved before countries holds (-priority, package).
        self.queue = [tuple(item) if len(item) == 3 else (item[0], item[1], "") for item in state["queue"]]
        heapq.heapify(self.queue)
        self.devs_done = set(state["devs_done"])
        self.pages_done = set(state["pages_done"])
        self.failures = state.get("failures", {})
        self.local = set(state.get("local", []))
        self.devs_done_local = set(state.get("devs_done_local", []))
        self.shipped_read = set(state.get("shipped_read", []))
        self.seeds_retried = set(state.get("seeds_retried", []))
        logging.info("resumed: %d apps, %d queued", len(self.apps), len(self.queue))

    def save(self):
        state = {
            "apps": self.apps,
            "seen": sorted(self.seen),
            "queue": self.queue,
            "devs_done": sorted(self.devs_done),
            "pages_done": sorted(self.pages_done),
            "failures": self.failures,
            "local": sorted(self.local),
            "devs_done_local": sorted(self.devs_done_local),
            "shipped_read": sorted(self.shipped_read),
            "seeds_retried": sorted(self.seeds_retried),
        }
        temp = self.path("state.json.tmp")
        with open(temp, "w") as f:
            json.dump(state, f)
        os.replace(temp, self.path("state.json"))

    def enqueue(self, package, priority, country=""):
        if package not in self.seen:
            self.seen.add(package)
            heapq.heappush(self.queue, (-priority, package, country))
        elif country and package not in self.apps and package + "|" + country not in self.seen:
            # Seen but not kept, perhaps unavailable where it was read: one
            # try in each country it turns up in (Codex on PR #916).
            self.seen.add(package + "|" + country)
            heapq.heappush(self.queue, (-priority, package, country))

    def links(self, page, priority, local=False, country=""):
        for package in APP_LINK.findall(page):
            if local:
                self.make_local(package, priority, country)
            else:
                self.enqueue(package, priority, country)

    def make_local(self, package, priority, country=""):
        """Marks [package] a country's own app, to be kept from the lower bar.
        One already fetched, in an earlier run say, is queued once more:
        turned down under the higher bar it may now be kept, and kept it may
        still have smaller siblings on its developer's page (Codex on PR #892)."""
        newly = package not in self.local
        self.local.add(package)
        if package not in self.seen:
            self.enqueue(package, priority, country)
        elif newly:
            heapq.heappush(self.queue, (-priority, package, country))

    def crawl_page(self, key, path, priority, country=""):
        if key in self.pages_done:
            return
        page = self.fetch(with_country(path, country))
        self.pages_done.add(key)
        if page:
            self.links(page, priority, country=country)

    def crawl_app(self, package, country=""):
        """Reads [package] in [country]'s Google Play, or, with none known (a
        seed), in the first country that has it."""
        path = "/store/apps/details?id=" + urllib.parse.quote(package) + "&hl=en"
        page = None
        for country in [country] if country else self.countries:
            page = self.fetch(with_country(path, country))
            if page is not None:
                break
        if page is None:
            return
        category = CATEGORY.search(page)
        # Games say so in their manifests, so the app needs no catalog for
        # them, and their pages mostly link to more games (maintainer,
        # 2026-11-22): neither kept nor followed.
        if category and category.group(1).startswith("GAME"):
            return
        installs = INSTALLS.search(page)
        count = int(installs.group(1)) if installs else 0
        local = package in self.local
        bar = self.local_min_installs if local else self.min_installs
        if category and count >= bar:
            self.apps[package] = [category.group(1), count]
        # Popular apps' neighbors are worth more; never follow from obscure ones.
        if count >= bar:
            self.links(page, count, country=country)
            for endpoint, dev_id in set(DEV_LINK.findall(page)):
                # Requested as linked, encoding and all (Codex on PR #889).
                dev = "/store/apps/" + endpoint + "?id=" + dev_id
                # A page fetched for a popular app is fetched again for a seed,
                # so its apps can be kept from the lower bar.
                done = self.devs_done_local if local else self.devs_done
                # Per country: a developer's page lists different apps in
                # each (Codex on PR #916).
                dev_key = dev + ("|" + country if country else "")
                if dev_key not in done:
                    dev_page = self.fetch(with_country(dev + "&hl=en", country))
                    # Done only once fetched: a slow-down retries the app, and
                    # with it this page (Codex on PR #889).
                    self.devs_done.add(dev_key)
                    if local:
                        self.devs_done_local.add(dev_key)
                    if dev_page:
                        self.links(dev_page, count // 2, local=local, country=country)

    def run(self, seeds, target, spacing, deadline=None, known=None):
        """Crawls until [target] apps are kept, the queue empties, or the
        monotonic clock passes [deadline]. Apps in [known] (package to
        category, the catalog already shipped) are kept without fetching
        them again, unless they're seeds, so a crawl whose saved state was
        lost doesn't fetch them all again; once the queue runs dry they are
        fetched after all, for the neighbors they lead to. True when the crawl
        stopped with the queue empty short of [target]: nothing left to find."""
        self.spacing = spacing
        known = known or {}
        for package, category in known.items():
            if not category.startswith("GAME"):
                self.apps.setdefault(package, [category, 0])
        for package in seeds:
            self.make_local(package, 10**12)
            # A seed not kept from the runner's own view, before countries,
            # gets one more try in each country (Codex on PR #916).
            if (self.countries != [""] and package not in self.apps
                    and package not in self.seeds_retried):
                self.seeds_retried.add(package)
                heapq.heappush(self.queue, (-10**12, package, ""))
        for country in self.countries:
            for category in CATEGORIES:
                # Each country adds 31 pages, and slow-downs ten minutes each:
                # stop in time, with what's done saved (Codex on PR #916).
                if deadline is not None and time.monotonic() >= deadline:
                    logging.info("out of time reading category pages")
                    self.save()
                    return False
                key = "category:" + category + (":" + country if country else "")
                try:
                    self.crawl_page(key, "/store/apps/category/" + category + "?hl=en", 10**11, country)
                except SlowDown:
                    logging.warning("slow down on category %s %s; waiting", category, country)
                    self.save()
                    time.sleep(600)
                except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                    # Not marked done, so the next run tries it again; the seeds
                    # and the other categories carry on (Codex on PR #889).
                    logging.warning("failed category %s %s: %s", category, country, error)
            self.save()
        fetched = 0
        while len(self.apps) < target:
            if not self.queue:
                # Nothing new left: read the apps kept unread from the shipped
                # catalog (a count of 0), for their neighbors (maintainer,
                # 2026-11-24: the crawl ran dry at 12,700 apps, with the
                # shipped ones' neighbors never visited).
                unread = sorted(p for p, (_, count) in self.apps.items()
                                if count == 0 and p not in self.shipped_read)
                if not unread:
                    break
                logging.info("queue empty; reading %d shipped apps for their neighbors", len(unread))
                for package in unread:
                    self.shipped_read.add(package)
                    heapq.heappush(self.queue, (-1, package, ""))
            if deadline is not None and time.monotonic() >= deadline:
                logging.info("out of time")
                break
            priority, package, country = heapq.heappop(self.queue)
            if package in known and package not in self.local and package not in self.shipped_read:
                # Kept already; its own page waits until nothing else is left.
                continue
            try:
                self.crawl_app(package, country)
            except KeyboardInterrupt:
                # Back in the queue, so the saved state still has it to fetch
                # (Codex on PR #889).
                heapq.heappush(self.queue, (priority, package, country))
                raise
            except SlowDown:
                logging.warning("asked to slow down after %d pages; waiting 10 minutes", fetched)
                heapq.heappush(self.queue, (-10**12, package, country))
                self.save()
                time.sleep(600)
                continue
            except (http.client.InvalidURL, ValueError) as error:
                # A link we can't request: retrying won't help, and one bad
                # link shouldn't end a run of hours (run 2 of 2026-11-21).
                logging.warning("bad link from %s, skipped: %s", package, error)
            except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                # One failed page shouldn't end a run of hours: queue it again,
                # behind what's waiting, up to MAX_ATTEMPTS (Codex on PR #889).
                attempts = self.failures.get(package, 0) + 1
                self.failures[package] = attempts
                if attempts < MAX_ATTEMPTS:
                    logging.warning("failed %s (attempt %d), will retry: %s", package, attempts, error)
                    heapq.heappush(self.queue, (priority // 2, package, country))
                else:
                    logging.warning("failed %s %d times, giving up: %s", package, attempts, error)
            fetched += 1
            if fetched % 100 == 0:
                self.save()
                logging.info("%d pages, %d apps, %d queued", fetched, len(self.apps), len(self.queue))
        self.save()
        logging.info("stopped: %d apps, %d queued", len(self.apps), len(self.queue))
        return not self.queue and len(self.apps) < target

    def write(self, out):
        rows = sorted(self.apps.items())
        with open(out, "w") as f:
            f.write("# Google Play category by package, from scripts/play-catalog/crawl.py.\n")
            for package, (category, _) in rows:
                f.write(package + "\t" + category + "\n")
        logging.info("wrote %d apps to %s", len(rows), out)


def read_catalog(path):
    """Package to category, from a catalog [write] made."""
    known = {}
    with open(path) as f:
        for line in f:
            if line.startswith("#") or "\t" not in line:
                continue
            package, category = line.rstrip("\n").split("\t", 1)
            known[package] = category
    return known


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--state", required=True, help="directory for the resumable state")
    parser.add_argument("--seeds", action="append", default=[], help="file of seed packages, one a line")
    parser.add_argument("--target", type=int, default=50000, help="stop once this many apps are kept")
    parser.add_argument("--min-installs", type=int, default=100000)
    parser.add_argument("--local-min-installs", type=int, default=10000,
                        help="the bar for seeds and their developers' other apps")
    parser.add_argument("--spacing", type=float, default=2.0, help="seconds between pages")
    parser.add_argument("--hours", type=float, help="stop after this long, state saved, to resume later")
    parser.add_argument("--write", help="write the catalog here instead of crawling")
    parser.add_argument("--known", help="a catalog already shipped: its apps are kept without fetching them again")
    parser.add_argument("--countries", default="",
                        help="comma-separated country codes whose Google Play is read (default: the runner's own)")
    args = parser.parse_args()

    log_dir = os.path.expanduser("~/.local/state/typelauncher")
    os.makedirs(log_dir, exist_ok=True)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[logging.StreamHandler(), logging.FileHandler(os.path.join(log_dir, "play-catalog.log"))],
    )
    logging.info("started: %s", " ".join(sys.argv[1:]))
    countries = [c.strip().upper() for c in args.countries.split(",") if c.strip()]
    crawl = Crawl(args.state, args.min_installs, args.local_min_installs, countries)
    try:
        if args.write:
            crawl.write(args.write)
        else:
            seeds = []
            for name in args.seeds:
                with open(name) as f:
                    seeds += [line.split("#")[0].strip() for line in f if line.split("#")[0].strip()]
            deadline = time.monotonic() + args.hours * 3600 if args.hours else None
            known = read_catalog(args.known) if args.known else {}
            logging.info("%d apps known from the shipped catalog", len(known))
            if crawl.run(seeds, args.target, args.spacing, deadline, known):
                # Every scheduled run would otherwise pass doing nothing, as
                # runs did for a day before 2026-11-24.
                logging.error("ran dry: %d apps, short of the target of %d, with nothing left to fetch",
                              len(crawl.apps), args.target)
                return 2
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
