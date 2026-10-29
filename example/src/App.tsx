import { useEffect, useState } from 'react';
import {
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
  Platform,
  Alert,
} from 'react-native';
import {
  checkVersion,
  completeFlexibleUpdate,
  addUpdateListener,
  type InstallStateEvent,
} from 'react-native-in-app-update';

export default function App() {
  const [downloadProgress, setDownloadProgress] = useState<number | null>(null);
  const [isUpdateReady, setIsUpdateReady] = useState(false);

  useEffect(() => {
    const subscription = addUpdateListener((event: InstallStateEvent) => {
      console.log('Update event:', event);
      if (typeof event.progress === 'number') {
        setDownloadProgress(Math.round(event.progress * 100));
      }
      if (event.status === 'DOWNLOADED') {
        setIsUpdateReady(true);
      }
    });

    return () => {
      subscription?.remove?.();
    };
  }, []);

  const handleCheckUpdate = async (force: 'Y' | 'N') => {
    try {
      const result = await checkVersion(force, {
        appStoreID: '1234567890', // Replace with your iOS App Store ID
        appName: 'My App',
      });
      console.log('Check version result:', result);
    } catch (error: any) {
      Alert.alert(
        'Update Error',
        error?.message || 'Failed to check for update'
      );
    }
  };

  const handleCompleteUpdate = async () => {
    try {
      await completeFlexibleUpdate();
    } catch (error: any) {
      Alert.alert(
        'Install Error',
        error?.message || 'Failed to complete update'
      );
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>In-App Update Demo</Text>
      <Text style={styles.subtitle}>Platform: {Platform.OS}</Text>

      {downloadProgress !== null && (
        <Text style={styles.progressText}>
          Download progress: {downloadProgress}%
        </Text>
      )}

      <TouchableOpacity
        style={styles.button}
        onPress={() => handleCheckUpdate('N')}
      >
        <Text style={styles.buttonText}>Check Flexible / Optional Update</Text>
      </TouchableOpacity>

      <TouchableOpacity
        style={[styles.button, styles.dangerButton]}
        onPress={() => handleCheckUpdate('Y')}
      >
        <Text style={styles.buttonText}>Check Immediate / Required Update</Text>
      </TouchableOpacity>

      {isUpdateReady && (
        <TouchableOpacity
          style={[styles.button, styles.successButton]}
          onPress={handleCompleteUpdate}
        >
          <Text style={styles.buttonText}>Restart to Apply Update</Text>
        </TouchableOpacity>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    backgroundColor: '#F5F5F5',
  },
  title: {
    fontSize: 22,
    fontWeight: 'bold',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    marginBottom: 24,
  },
  progressText: {
    fontSize: 16,
    color: '#007AFF',
    marginBottom: 16,
  },
  button: {
    backgroundColor: '#007AFF',
    paddingVertical: 14,
    paddingHorizontal: 24,
    borderRadius: 8,
    marginVertical: 8,
    width: '100%',
    alignItems: 'center',
  },
  dangerButton: {
    backgroundColor: '#FF3B30',
  },
  successButton: {
    backgroundColor: '#34C759',
  },
  buttonText: {
    color: '#FFF',
    fontSize: 16,
    fontWeight: '600',
  },
});
