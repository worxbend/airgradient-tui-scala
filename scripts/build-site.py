#!/usr/bin/env python3
"""Stage the dependency-free microsite with the actual Glyphora captures."""
import shutil
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit

root = Path(__file__).resolve().parents[1]
output = root / "dist/site"
if output.exists():
    shutil.rmtree(output)
shutil.copytree(root / "site", output)
(output / "assets").mkdir()
for name in ("mark.svg", "dashboard.svg", "history.svg", "settings.svg"):
    shutil.copy2(root / "docs/assets" / name, output / "assets" / name)
(output / ".nojekyll").touch()


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.links = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            assert attrs["id"] not in self.ids, f"Duplicate ID: {attrs['id']}"
            self.ids.add(attrs["id"])
        for key in ("href", "src"):
            if key in attrs:
                self.links.append(attrs[key])


page = Links()
page.feed((output / "index.html").read_text())
for link in page.links:
    url = urlsplit(link)
    if url.scheme or url.netloc:
        continue
    assert not url.path.startswith("/"), f"Use project-relative URLs for GitHub Pages: {link}"
    target = output / unquote(url.path)
    assert target.exists(), f"Missing local asset: {link}"
    if url.fragment and not url.path:
        assert unquote(url.fragment) in page.ids, f"Missing anchor: {link}"
print(f"Microsite built and local links checked: {output.relative_to(root)}")
