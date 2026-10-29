# react-native-in-app-update

Seamless in-app update flow for React Native: Google Play In-App Updates (Immediate and Flexible) on Android and iTunes App Store version checking with customizable update prompts on iOS.

---

## Features

- **Android (Google Play In-App Updates)**:
  - Supports **Flexible** update (`'N'`) with background downloading and event listener for download progress.
  - Supports **Immediate / Mandatory** update (`'Y'`) with full-screen flow.
  - Native resume lifecycle checks for unfinished or pending updates.
  - Built-in dialogs for update readiness and completion.
- **iOS (App Store Version Check)**:
  - Fetches the latest published version from iTunes Lookup API using `appStoreID`.
  - Compares the App Store release version with the locally installed `CFBundleShortVersionString`.
  - Configurable required (`'Y'`) or optional (`'N'`) prompt dialogs.
  - Automatically redirects users directly to the App Store page.
  - Re-prompts on resume when a required update is pending.

---

## Installation

```sh
npm install react-native-in-app-update
# or
yarn add react-native-in-app-update
```

### iOS

Run CocoaPods installation:

```sh
cd ios && pod install
```

### Android

No additional setup is required. The library automatically links `AppUpdatePackage` and includes Google Play In-App Update SDK (`com.google.android.play:app-update`).

---

## Usage

```typescript
import {
  checkVersion,
  completeFlexibleUpdate,
  addUpdateListener,
} from 'react-native-in-app-update';

// 1. Check for an update
const checkAppUpdate = async () => {
  try {
    const result = await checkVersion('N', {
      appStoreID: '1234567890', // Required for iOS
      appName: 'My App',        // Optional: App name in iOS prompt
      customTitle: 'Update Available', // Optional
      customMessage: 'A new version is available. Update now?', // Optional
    });

    console.log('Update check result:', result);
  } catch (error) {
    console.error('Update check failed:', error);
  }
};

// 2. Listen for download progress (Android Flexible update)
useEffect(() => {
  const subscription = addUpdateListener((event) => {
    console.log('Update status:', event.status);
    if (event.status === 'DOWNLOADING') {
      console.log(`Progress: ${(event.progress * 100).toFixed(0)}%`);
    } else if (event.status === 'DOWNLOADED') {
      console.log('Update downloaded and ready to install');
    }
  });

  return () => {
    subscription?.remove?.();
  };
}, []);

// 3. Complete flexible update and restart the app (Android)
const applyDownloadedUpdate = async () => {
  await completeFlexibleUpdate();
};
```

---

## API Reference

### `checkVersion(forceUpdate, options)`

Checks for update availability and initiates the platform-specific update flow.

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `forceUpdate` | `'Y' \| 'N'` | Yes | `'Y'` for required/immediate update, `'N'` for optional/flexible update. |
| `options` | `UpdateOptions` | Optional (iOS required) | Configuration options. |

#### `UpdateOptions`

| Option | Type | Platform | Description |
| :--- | :--- | :--- | :--- |
| `appStoreID` | `string` | iOS | **Required on iOS.** The numeric Apple App Store ID for your application. |
| `appName` | `string` | iOS | Display name of the application in update prompts. Defaults to `CFBundleDisplayName`. |
| `customTitle` | `string` | iOS | Custom title for the alert dialog. |
| `customMessage` | `string` | iOS | Custom message body for the alert dialog. |

### `completeFlexibleUpdate()`

Completes a downloaded flexible update on Android and restarts the app. Resolves to `{ status: 'INSTALLED' }`.

### `addUpdateListener(callback)`

Listens for update state changes during flexible and immediate flows.

---

## License

MIT
