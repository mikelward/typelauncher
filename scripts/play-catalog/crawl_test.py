#!/usr/bin/env python3
"""Tests crawl.py against stubbed Google Play pages: no network."""

import os
import sys
import tempfile
import unittest
import urllib.error

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import crawl  # noqa: E402


def app_page(category, installs, similar=(), dev=None):
    links = "".join('<a href="/store/apps/details?id=%s">x</a>' % p for p in similar)
    dev_link = '<a href="/store/apps/dev?id=%s">dev</a>' % dev if dev else ""
    return ('{"applicationCategory":"%s"} ["%s+",%d,%d] %s %s'
            % (category, format(installs, ","), installs, installs + 7, links, dev_link))


PAGES = {
    "/store/apps/details?id=com.popular.weather&hl=en":
        app_page("WEATHER", 5_000_000, similar=["com.other.radar", "com.tiny.app"], dev="123"),
    "/store/apps/details?id=com.other.radar&hl=en": app_page("WEATHER", 1_000_000),
    "/store/apps/details?id=com.tiny.app&hl=en": app_page("TOOLS", 500, similar=["com.never.followed"]),
    "/store/apps/dev?id=123&hl=en": '<a href="/store/apps/details?id=com.popular.sibling">s</a>',
    "/store/apps/details?id=com.popular.sibling&hl=en": app_page("WEATHER", 200_000),
}


class CrawlTest(unittest.TestCase):
    def setUp(self):
        self.fetched = []

        def fetch(path):
            self.fetched.append(path)
            return PAGES.get(path)

        self.real_fetch = crawl.fetch
        crawl.fetch = fetch
        self.state = tempfile.mkdtemp()

    def tearDown(self):
        crawl.fetch = self.real_fetch

    def run_crawl(self, target=100):
        c = crawl.Crawl(self.state, min_installs=100_000)
        c.run(["com.popular.weather"], target, spacing=0)
        return c

    def test_keepsPopularApps_followsTheirNeighborsAndDevelopers(self):
        c = self.run_crawl()
        self.assertEqual(
            {"com.popular.weather": ["WEATHER", 5_000_000], "com.other.radar": ["WEATHER", 1_000_000],
             "com.popular.sibling": ["WEATHER", 200_000]},
            c.apps,
        )

    def test_neverFollowsFromObscureApps(self):
        self.run_crawl()
        self.assertNotIn("/store/apps/details?id=com.never.followed&hl=en", self.fetched)

    def test_neverSearches(self):
        # play.google.com/robots.txt disallows /store/search.
        self.run_crawl()
        self.assertFalse([p for p in self.fetched if p.startswith("/store/search")])

    def test_resumesWhereItStopped(self):
        self.run_crawl(target=1)
        before = len(self.fetched)
        c = self.run_crawl()
        self.assertEqual(3, len(c.apps))
        # The app already fetched isn't fetched again.
        self.assertEqual(1, self.fetched.count("/store/apps/details?id=com.popular.weather&hl=en"))
        self.assertGreater(len(self.fetched), before)

    def test_stopsWhenOutOfTime_withItsStateSaved(self):
        c = crawl.Crawl(self.state, min_installs=100_000)
        c.run(["com.popular.weather"], 100, spacing=0, deadline=0)
        self.assertEqual({}, c.apps)
        self.assertEqual(1, len(crawl.Crawl(self.state, min_installs=100_000).queue))

    def test_retriesAnAppAfterANetworkFailure(self):
        failures = {"/store/apps/details?id=com.other.radar&hl=en": 1}
        stub = crawl.fetch

        def flaky(path):
            if failures.get(path):
                failures[path] -= 1
                self.fetched.append(path)
                raise urllib.error.URLError("timed out")
            return stub(path)

        crawl.fetch = flaky
        c = self.run_crawl()
        self.assertIn("com.other.radar", c.apps)

    def test_givesUpOnAnAppThatKeepsFailing(self):
        stub = crawl.fetch

        def broken(path):
            if "com.other.radar" in path:
                self.fetched.append(path)
                raise urllib.error.URLError("timed out")
            return stub(path)

        crawl.fetch = broken
        c = self.run_crawl()
        self.assertNotIn("com.other.radar", c.apps)
        self.assertEqual(crawl.MAX_ATTEMPTS, self.fetched.count("/store/apps/details?id=com.other.radar&hl=en"))

    def test_retriesADeveloperPageAfterASlowDown(self):
        slowed = {"done": False}
        stub = crawl.fetch

        def slow_once(path):
            if path.startswith("/store/apps/dev") and not slowed["done"]:
                slowed["done"] = True
                raise crawl.SlowDown()
            return stub(path)

        crawl.fetch = slow_once
        real_sleep = crawl.time.sleep
        crawl.time.sleep = lambda seconds: None
        try:
            c = self.run_crawl()
        finally:
            crawl.time.sleep = real_sleep
        self.assertIn("com.popular.sibling", c.apps)

    def test_spacesEveryRequest_developerPagesToo(self):
        events = []
        stub = crawl.fetch
        clock = {"now": 0.0}

        def timed(path):
            events.append(clock["now"])
            return stub(path)

        def sleep(seconds):
            clock["now"] += seconds

        crawl.fetch = timed
        real_sleep, real_monotonic = crawl.time.sleep, crawl.time.monotonic
        crawl.time.sleep, crawl.time.monotonic = sleep, lambda: clock["now"]
        try:
            crawl.Crawl(self.state, min_installs=100_000).run(["com.popular.weather"], 100, spacing=2)
        finally:
            crawl.time.sleep, crawl.time.monotonic = real_sleep, real_monotonic
        gaps = [later - earlier for earlier, later in zip(events, events[1:])]
        self.assertTrue(gaps)
        self.assertGreaterEqual(min(gaps), 2)

    def test_aFailedCategoryPage_doesntEndTheRun(self):
        stub = crawl.fetch

        def flaky(path):
            if path.startswith("/store/apps/category/FINANCE"):
                raise urllib.error.URLError("timed out")
            return stub(path)

        crawl.fetch = flaky
        c = self.run_crawl()
        self.assertIn("com.popular.weather", c.apps)
        self.assertNotIn("category:FINANCE", c.pages_done)

    def test_followsNamedDeveloperLinks_asLinked(self):
        PAGES["/store/apps/details?id=com.named.dev&hl=en"] = (
            app_page("TOOLS", 1_000_000) + '<a href="/store/apps/developer?id=Example+Studio">d</a>')
        PAGES["/store/apps/developer?id=Example+Studio&hl=en"] = (
            '<a href="/store/apps/details?id=com.example.studio">s</a>')
        PAGES["/store/apps/details?id=com.example.studio&hl=en"] = app_page("TOOLS", 300_000)
        try:
            c = crawl.Crawl(self.state, min_installs=100_000)
            c.run(["com.named.dev"], 100, spacing=0)
        finally:
            for key in [k for k in PAGES if "named.dev" in k or "Example" in k or "example.studio" in k]:
                del PAGES[key]
        self.assertIn("com.example.studio", c.apps)

    def test_anInterruptedApp_isStillQueuedWhenSaved(self):
        stub = crawl.fetch

        def interrupted(path):
            if "com.popular.weather" in path:
                raise KeyboardInterrupt()
            return stub(path)

        crawl.fetch = interrupted
        c = crawl.Crawl(self.state, min_installs=100_000)
        with self.assertRaises(KeyboardInterrupt):
            c.run(["com.popular.weather"], 100, spacing=0)
        c.save()
        resumed = crawl.Crawl(self.state, min_installs=100_000)
        self.assertEqual(["com.popular.weather"], [package for _, package in resumed.queue])

    def test_writesPackageAndCategory(self):
        c = self.run_crawl()
        out = os.path.join(self.state, "catalog.tsv")
        c.write(out)
        with open(out) as f:
            lines = [line for line in f.read().splitlines() if not line.startswith("#")]
        self.assertEqual("com.other.radar\tWEATHER", lines[0])
        self.assertEqual(3, len(lines))


if __name__ == "__main__":
    unittest.main()
