#!/usr/bin/env python3
"""Check the app identity boundary without requiring the Android SDK.

Fossify Commons derives launcher alias names from the installed application ID
with the debug suffix removed. Real classes must still resolve in the inherited
source namespace. Keeping those two identities distinct prevents an installable
rebrand from breaking launcher icons or activity startup.
"""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


def check_identity():
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    properties = dict(
        line.split("=", 1)
        for line in (ROOT / "gradle.properties").read_text().splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )
    app_id = properties.get("APP_ID")
    require(app_id == "app.inkpilot.android", "standalone application ID changed")
    build = (ROOT / "app/build.gradle.kts").read_text()
    namespace_match = re.search(r'namespace\s*=\s*"([^"]+)"', build)
    namespace = namespace_match.group(1) if namespace_match else ""
    require(namespace == "org.fossify.messages", "inherited source namespace changed")
    require('applicationId = project.property("APP_ID").toString()' in build,
            "application ID must come from APP_ID")
    require('applicationIdSuffix = ".debug"' in build, "debug install suffix is missing")
    require('manifestPlaceholders["launcherAliasPackage"] = applicationId!!' in build,
            "launcher aliases must use the base application ID before debug suffixes")

    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    require(application is not None, "application element is missing")
    if application is None:
        return errors
    class_names = set()
    for element in application.iter():
        if element.tag in {"application", "activity", "service", "receiver"}:
            class_names.add(element.get(ANDROID + "name", ""))
        for attribute in ("parentActivityName", "targetActivity"):
            value = element.get(ANDROID + attribute)
            if value:
                class_names.add(value)
    for name in class_names:
        if name.startswith("."):
            qualified = namespace + name
            source = ROOT / "app/src/main/kotlin" / Path(*qualified.split(".")).with_suffix(".kt")
            require(source.is_file(), f"manifest class does not resolve: {qualified}")

    aliases = application.findall("activity-alias")
    require(bool(aliases), "launcher aliases are missing")
    alias_prefix = "${launcherAliasPackage}.activities.SplashActivity"
    require(any(alias.get(ANDROID + "name") == alias_prefix for alias in aliases),
            "Commons default launcher component is missing")
    launchers = []
    for alias in aliases:
        name = alias.get(ANDROID + "name", "")
        require(name == alias_prefix or name.startswith(alias_prefix + "."),
                f"launcher alias does not follow the base application ID: {name}")
        require(alias.get(ANDROID + "targetActivity") == ".activities.SplashActivity",
                f"launcher alias no longer targets the inherited activity: {name}")
        if alias.find("intent-filter/category[@" + ANDROID + "name='android.intent.category.LAUNCHER']") is not None:
            if alias.get(ANDROID + "enabled", "true") != "false":
                launchers.append(alias)
    require(len(launchers) == 1, "exactly one launcher alias must be enabled by default")
    providers = application.findall("provider")
    require(bool(providers), "file provider is missing")
    for provider in providers:
        require(provider.get(ANDROID + "authorities") == "${applicationId}.provider",
                "provider authority must follow the installed application ID")

    base_names = set()
    for source_set in ("main", "debug"):
        for path in (ROOT / f"app/src/{source_set}/res").glob("values*/*.xml"):
            for string in ET.parse(path).getroot().findall("string"):
                if string.get("name") in {"app_name", "app_launcher_name"}:
                    if source_set == "main" and path.parent.name == "values":
                        base_names.add(string.get("name"))
                    expected = "inkPilot Debug" if source_set == "debug" else "inkPilot"
                    require(string.text == expected and string.get("translatable") == "false",
                            f"product name must remain nontranslatable: {path.relative_to(ROOT)}")
    require(base_names == {"app_name", "app_launcher_name"}, "default product names are missing")
    return errors


if __name__ == "__main__":
    failures = check_identity()
    for failure in failures:
        print(f"FAIL: {failure}")
    if not failures:
        print("PASS: standalone IDs, source classes, launcher aliases, providers, and branding agree")
    sys.exit(bool(failures))
