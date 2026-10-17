# Update checks

OpenPressure is installed from [GitHub Releases](https://github.com/scolastico/open-pressure/releases), so it checks there for newer versions. Checks are on by default and can be turned off.

## How it works

When the app opens, it checks at most once a day. It sends one anonymous HTTPS request to GitHub's public releases API for this repository. The request contains only the app version in its user agent; it carries no readings, monitor details, account or device identifiers. GitHub receives the phone's IP address like any website visit. A failed or rate-limited check is silently retried a day later.

Only a published, non-prerelease release of this repository with a newer version counts. Its link must point to this repository's release page.

## The update dialog

| Choice | Effect |
| --- | --- |
| **Open download page** | Opens the release page in your browser. If you do not install it, the app asks again in three days. |
| **Remind me later** | Asks again in three days. Dismissing the dialog does the same. |
| **Skip this version** | Never asks about this version again. A later version is offered normally. |

The app does not download or install anything itself. Install the APK from the release page; Android only replaces the app if the update is signed by the same developer. Readings and settings are kept across updates.

## Turning checks off

Open **Devices → Advanced → Update checks** and choose **Turn off update checks**. No further requests are made. Turning checks back on runs a check the next time the app opens.

Implementation: `org.openpressure.app.updates.UpdateCheck`. Tests cover version ordering including release candidates, release filtering, remind/skip scope, the daily interval, the toggle, and a loopback request that confirms no authorization or cookie headers are sent.
