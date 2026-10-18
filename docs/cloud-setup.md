# Setting up OneDrive and Google Drive

NibSync talks to OneDrive and Google Drive as a registered OAuth app. The registrations belong
to whoever builds the app; their ids go into `cloud.properties` (not checked in, see
`cloud.properties.example`). Without them the services show "not set up".

## Microsoft (OneDrive)

1. https://entra.microsoft.com → Applications → App registrations → New registration.
   - Name `NibSync`; supported accounts: any organisational directory and personal accounts.
   - Redirect URI: platform "Public client/native (mobile & desktop)", `http://localhost`.
2. Authentication: add the redirect URI `de.nibsync.app://oauth` (Android); "Allow public
   client flows": yes.
3. API permissions → Microsoft Graph → delegated: `Files.ReadWrite.AppFolder`,
   `offline_access` (NibSync then only sees its folder "Apps/NibSync").
4. `onedrive.clientId` = the Application (client) ID.

## Google (Google Drive)

1. https://console.cloud.google.com → new project `NibSync`; enable the Google Drive API.
2. Google Auth Platform: app name, audience "External", test users, data access scope
   `https://www.googleapis.com/auth/drive.file` (NibSync only sees files it created).
3. Clients:
   - "Desktop app" → `google.desktop.clientId` and `google.desktop.clientSecret` (not secret
     for desktop apps by Google's design).
   - "Android": package `de.nibsync.app`, SHA-1 of the signing certificate
     (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android` for debug
     builds) → `google.android=true`. The app needs Google Play services.
4. Publish the app (Audience) so logins do not expire after 7 days (testing mode).

## How the logins work

- PC: authorisation code with PKCE, the browser returns to a loopback address
  (`http://localhost:<port>` for Microsoft, `http://127.0.0.1:<port>` for Google). The refresh
  token is stored in `<data>/sync-account.properties` (owner-only).
- Android, OneDrive: the same flow with the redirect `de.nibsync.app://oauth`
  (`OAuthRedirectActivity`). Android, Google: Google's authorisation API from Play services
  gives access tokens directly; no refresh token is stored.
