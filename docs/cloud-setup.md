# Setting up OneDrive and Google Drive

NibSync talks to OneDrive and Google Drive as a registered OAuth app. This is done once for
the app by its publisher, not by its users. The client ids are built in from `cloud.properties`
(not checked in, see `cloud.properties.example`); without an id the service shows "not set up
in this version".

## Microsoft (OneDrive)

1. https://entra.microsoft.com → Applications → App registrations → New registration.
   - Name `NibSync`; supported accounts: any organisational directory and personal accounts.
   - Redirect URI: platform "Public client/native (mobile & desktop)", `http://localhost`.
2. Authentication: add the redirect URI `com.stemaker.nibsync://oauth` (Android); "Allow public
   client flows": yes.
3. API permissions → Microsoft Graph → delegated: `Files.ReadWrite.AppFolder`,
   `offline_access` (NibSync then only sees its folder "Apps/NibSync").
4. `onedrive.clientId` = the Application (client) ID.

## Google (Google Drive)

1. https://console.cloud.google.com → new project `NibSync`; enable the Google Drive API.
2. Google Auth Platform: app name, audience "External", test users, data access scope
   `https://www.googleapis.com/auth/drive.file` (NibSync only sees files it created).
3. Clients:
   - "Desktop app" → `google.desktop.clientId` and `google.desktop.clientSecret` (Google
     shows the secret only when the client is created; not secret for desktop apps by
     Google's design).
   - "Android": package `com.stemaker.nibsync`, SHA-1 of the signing certificate
     (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android` for debug
     builds). Nothing to build in: Google recognises the app by package and certificate. Google Drive is offered whenever Google Play services are present; without
     this client Google answers `UNREGISTERED_ON_API_CONSOLE` (or DEVELOPER_ERROR) after the
     account is chosen, and the app then shows the package name and the SHA-1 to register.
4. Publish the app (Audience) so logins do not expire after 7 days (testing mode).

## How the logins work

- PC: authorisation code with PKCE, the browser returns to a loopback address
  (`http://localhost:<port>` for Microsoft, `http://127.0.0.1:<port>` for Google). The refresh
  token is stored in `<data>/sync-account.properties` (owner-only).
- Android, OneDrive: the same flow with the redirect `com.stemaker.nibsync://oauth`
  (`OAuthRedirectActivity`). Android, Google: Google's authorisation API from Play services
  gives access tokens directly; no refresh token is stored.
