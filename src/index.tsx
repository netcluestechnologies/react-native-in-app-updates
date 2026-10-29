import {
  Alert,
  DeviceEventEmitter,
  NativeEventEmitter,
  NativeModules,
  Platform,
  type EmitterSubscription,
} from 'react-native';

const LINKING_ERROR =
  `The package 'react-native-in-app-update' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go\n';

const nativeUpdates = NativeModules.InAppUpdates
  ? NativeModules.InAppUpdates
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

export interface UpdateOptions {
  /** iOS App Store ID (required for iOS) */
  appStoreID?: string;
  /** Application display name used in the update dialog */
  appName?: string;
  /** Custom title for the update dialog */
  customTitle?: string;
  /** Custom message for the update dialog */
  customMessage?: string;
}

export type UpdateFlow = 'Y' | 'N';

export interface UpdateResult {
  status:
    | 'STARTED'
    | 'NOT_STARTED'
    | 'NOT_AVAILABLE'
    | 'ALREADY_IN_PROGRESS'
    | 'DOWNLOADED'
    | 'INSTALLED'
    | 'UPDATE_TYPE_NOT_ALLOWED'
    | 'NOT_APPLICABLE'
    | string;
  [key: string]: any;
}

export interface InstallStateEvent {
  status:
    | 'PENDING'
    | 'DOWNLOADING'
    | 'DOWNLOADED'
    | 'INSTALLING'
    | 'INSTALLED'
    | 'FAILED'
    | 'CANCELLED'
    | 'ACCEPTED'
    | 'STARTED'
    | 'NOT_STARTED'
    | 'UNKNOWN'
    | string;
  bytesDownloaded?: number;
  totalBytesToDownload?: number;
  progress?: number;
  errorCode?: number;
  value?: number;
}

/**
 * Check for an update and start the platform's required or optional flow.
 *
 * @param forceUpdate 'Y' for required/immediate, 'N' for optional/flexible.
 * @param options Update configuration (appStoreID required for iOS).
 * @returns Promise resolving to update result.
 */
export const checkVersion = async (
  forceUpdate: UpdateFlow,
  options: UpdateOptions = {}
): Promise<UpdateResult | void> => {
  const flow =
    typeof forceUpdate === 'string'
      ? (forceUpdate.trim().toUpperCase() as UpdateFlow)
      : forceUpdate;

  if (flow !== 'Y' && flow !== 'N') {
    Alert.alert(
      'Invalid Update Type',
      "Update type must be 'Y' (required) or 'N' (optional)."
    );
    return;
  }

  if (
    !NativeModules.InAppUpdates ||
    typeof nativeUpdates.checkVersion !== 'function'
  ) {
    Alert.alert(
      'InAppUpdates Unavailable',
      'The InAppUpdates native module is unavailable. Rebuild the app with the native module registered.'
    );
    return;
  }

  if (
    typeof options !== 'object' ||
    options === null ||
    Array.isArray(options)
  ) {
    Alert.alert(
      'Invalid Update Configuration',
      'Update configuration must be an object.'
    );
    return;
  }

  if (Platform.OS === 'ios') {
    if (
      typeof options.appStoreID !== 'string' ||
      options.appStoreID.trim().length === 0
    ) {
      Alert.alert(
        'Invalid Update Configuration',
        'A valid appStoreID is required for iOS in-app updates.'
      );
      return;
    }

    return nativeUpdates.checkVersion(flow, options);
  }

  return nativeUpdates.checkVersion(flow);
};

/**
 * Completes a flexible update downloaded on Android and restarts the app.
 */
export const completeFlexibleUpdate =
  async (): Promise<UpdateResult | void> => {
    if (Platform.OS === 'android') {
      if (
        !NativeModules.InAppUpdates ||
        typeof nativeUpdates.completeFlexibleUpdate !== 'function'
      ) {
        return;
      }
      return nativeUpdates.completeFlexibleUpdate();
    }
    return Promise.resolve({ status: 'NOT_APPLICABLE' });
  };

/**
 * Listen for update state changes and download progress (primarily on Android).
 */
export const addUpdateListener = (
  listener: (event: InstallStateEvent) => void
): EmitterSubscription => {
  if (Platform.OS === 'android') {
    return DeviceEventEmitter.addListener('inAppUpdateState', listener);
  }

  const emitter = new NativeEventEmitter(
    NativeModules.InAppUpdates ? nativeUpdates : undefined
  );
  return emitter.addListener(
    'inAppUpdateState',
    listener as (...args: any[]) => void
  );
};

export default {
  checkVersion,
  completeFlexibleUpdate,
  addUpdateListener,
};
