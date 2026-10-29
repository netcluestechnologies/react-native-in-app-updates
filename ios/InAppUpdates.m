#import "InAppUpdates.h"
#import <UIKit/UIKit.h>

@interface InAppUpdates ()
@property (nonatomic, assign) BOOL requiredUpdatePending;
@property (nonatomic, assign) BOOL requiredAlertVisible;
@property (nonatomic, assign) BOOL updateAlertVisible;
@property (nonatomic, assign) BOOL updateCheckInProgress;
@property (nonatomic, copy) NSString *requiredAppName;
@property (nonatomic, copy) NSString *requiredVersion;
@property (nonatomic, copy) NSString *requiredAppStoreID;
@property (nonatomic, copy) NSString *customTitle;
@property (nonatomic, copy) NSString *customMessage;
@end

@implementation InAppUpdates

RCT_EXPORT_MODULE(InAppUpdates);

+ (BOOL)requiresMainQueueSetup
{
  return YES;
}

#pragma mark - React Native methods

RCT_REMAP_METHOD(checkVersion,
                 checkVersionWithFlow:(NSString *)flow
                 configuration:(id)configuration
                 resolver:(RCTPromiseResolveBlock)resolve
                 rejecter:(RCTPromiseRejectBlock)reject)
{
  if (![flow isEqualToString:@"Y"] && ![flow isEqualToString:@"N"]) {
    reject(@"E_INVALID_UPDATE_TYPE", @"Update type must be 'Y' (required) or 'N' (optional).", nil);
    return;
  }

  if (configuration != nil && configuration != [NSNull null] &&
      ![configuration isKindOfClass:[NSDictionary class]]) {
    reject(@"E_INVALID_CONFIGURATION", @"Update configuration must be an object.", nil);
    return;
  }

  NSDictionary *options = [configuration isKindOfClass:[NSDictionary class]] ? configuration : @{};
  id appStoreIDValue = options[@"appStoreID"];
  NSString *appStoreID = [appStoreIDValue isKindOfClass:[NSString class]]
    ? [(NSString *)appStoreIDValue stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]]
    : nil;

  if (appStoreID.length == 0 ||
      ![self isValidAppStoreID:appStoreID]) {
    NSLog(@"InAppUpdates received a missing or invalid App Store ID.");
    reject(@"E_INVALID_APP_STORE_ID", @"A valid numeric App Store ID is required.", nil);
    return;
  }

  @synchronized (self) {
    if (self.updateCheckInProgress || self.updateAlertVisible) {
      reject(@"E_UPDATE_CHECK_IN_PROGRESS", @"An update check or prompt is already in progress.", nil);
      return;
    }
    self.updateCheckInProgress = YES;
  }

  [self checkForUpdateRequired:[flow isEqualToString:@"Y"]
                configuration:options
                   appStoreID:appStoreID
                      resolver:resolve
                      rejecter:reject];
}

#pragma mark - App Store lookup

- (void)checkForUpdateRequired:(BOOL)required
                 configuration:(NSDictionary *)configuration
                    appStoreID:(NSString *)appStoreID
                      resolver:(RCTPromiseResolveBlock)resolve
                      rejecter:(RCTPromiseRejectBlock)reject
{
  __block BOOL didFinish = NO;
  void (^finish)(void) = ^{
    if (didFinish) return;
    didFinish = YES;
    @synchronized (self) {
      self.updateCheckInProgress = NO;
    }
  };
  void (^rejectAndFinish)(NSString *, NSString *, NSError *) = ^(NSString *code, NSString *message, NSError *error) {
    finish();
    reject(code, message, error);
  };

  NSString *lookupString = [NSString stringWithFormat:@"https://itunes.apple.com/lookup?id=%@", appStoreID];
  NSURL *url = [NSURL URLWithString:lookupString];

  if (!url) {
    rejectAndFinish(@"E_INVALID_LOOKUP_URL", @"Invalid App Store lookup URL.", nil);
    return;
  }

  NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
  request.timeoutInterval = 15.0;
  [request setValue:@"application/json" forHTTPHeaderField:@"Accept"];

  NSURLSessionDataTask *task =
  [[NSURLSession sharedSession] dataTaskWithRequest:request
                                  completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
    if (error) {
      rejectAndFinish(@"E_APP_STORE_LOOKUP_FAILED", error.localizedDescription ?: @"App Store lookup request failed.", error);
      return;
    }

    if (![response isKindOfClass:[NSHTTPURLResponse class]]) {
      rejectAndFinish(@"E_APP_STORE_LOOKUP_FAILED", @"Invalid response from the App Store lookup service.", nil);
      return;
    }

    NSHTTPURLResponse *http = (NSHTTPURLResponse *)response;
    if (http.statusCode < 200 || http.statusCode >= 300 || data == nil) {
      rejectAndFinish(@"E_APP_STORE_LOOKUP_FAILED", [NSString stringWithFormat:@"App Store lookup returned HTTP %ld.", (long)http.statusCode], nil);
      return;
    }

    NSError *jsonError = nil;
    id json = [NSJSONSerialization JSONObjectWithData:data options:0 error:&jsonError];
    if (jsonError || ![json isKindOfClass:[NSDictionary class]]) {
      rejectAndFinish(@"E_INVALID_APP_STORE_RESPONSE", @"Could not parse the App Store lookup response.", jsonError);
      return;
    }

    NSDictionary *root = (NSDictionary *)json;
    id resultsValue = root[@"results"];
    if (![resultsValue isKindOfClass:[NSArray class]] || [resultsValue count] == 0 ||
        ![resultsValue[0] isKindOfClass:[NSDictionary class]]) {
      rejectAndFinish(@"E_APP_STORE_LISTING_NOT_FOUND", @"No App Store listing was found for this Apple ID.", nil);
      return;
    }

    NSDictionary *listing = resultsValue[0];
    NSString *storeBundleID = listing[@"bundleId"];
    NSString *installedBundleID = [[NSBundle mainBundle] bundleIdentifier];

    if (![storeBundleID isKindOfClass:[NSString class]] ||
        ![installedBundleID isKindOfClass:[NSString class]] ||
        ![storeBundleID isEqualToString:installedBundleID]) {
      rejectAndFinish(@"E_APP_STORE_LISTING_MISMATCH", @"The App Store listing bundle ID does not match the installed app.", nil);
      return;
    }

    NSString *storeVersion = listing[@"version"];
    NSString *currentVersion = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleShortVersionString"];

    if (![storeVersion isKindOfClass:[NSString class]] ||
        ![currentVersion isKindOfClass:[NSString class]] ||
        ![self isValidVersion:storeVersion] ||
        ![self isValidVersion:currentVersion]) {
      rejectAndFinish(@"E_INVALID_VERSION", @"The installed or App Store version is missing or invalid.", nil);
      return;
    }

    BOOL updateAvailable = ([self compareVersion:storeVersion to:currentVersion] == NSOrderedDescending);
    NSDictionary *result = @{
      @"status": updateAvailable ? @"STARTED" : @"NOT_AVAILABLE"
    };

    if (!updateAvailable) {
      finish();
      resolve(result);
      return;
    }

    dispatch_async(dispatch_get_main_queue(), ^{
      UIViewController *top = [self topViewController];
      if (!top) {
        rejectAndFinish(@"E_NO_VIEW_CONTROLLER",
                        @"Unable to present the update prompt because no active view controller was found.",
                        nil);
        return;
      }

      NSString *appName = [self nonEmptyStringForKey:@"appName" configuration:configuration];
      if (appName == nil) {
        appName = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleDisplayName"];
      }
      if (![appName isKindOfClass:[NSString class]] || appName.length == 0) {
        appName = @"App";
      }

      if (required) {
        self.requiredAppName = appName;
        self.requiredVersion = storeVersion;
        self.requiredAppStoreID = appStoreID;
        self.customTitle = [self nonEmptyStringForKey:@"customTitle" configuration:configuration];
        self.customMessage = [self nonEmptyStringForKey:@"customMessage" configuration:configuration];
      }

      [self presentUpdateAlertFrom:top
                          appName:appName
                          version:storeVersion
                          appStoreID:appStoreID
                            title:[self nonEmptyStringForKey:@"customTitle"
                                                configuration:configuration]
                          message:[self nonEmptyStringForKey:@"customMessage"
                                                  configuration:configuration]
                          required:required
                        completion:^{
          finish();
          resolve(result);
      }];
    });
  }];

  [task resume];
}

#pragma mark - Update alert

- (void)presentUpdateAlertFrom:(UIViewController *)viewController
                       appName:(NSString *)appName
                       version:(NSString *)version
                    appStoreID:(NSString *)appStoreID
                         title:(NSString *)customTitle
                       message:(NSString *)customMessage
                      required:(BOOL)required
                    completion:(void (^)(void))completion
{
  NSString *versionText = version.length > 0
    ? [NSString stringWithFormat:@" (%@)", version]
    : @"";

  NSString *defaultTitle = required
    ? [NSString stringWithFormat:@"%@ needs an update", appName]
    : [NSString stringWithFormat:@"Update %@?", appName];

  NSString *defaultMessage = required
    ? [NSString stringWithFormat:@"To continue using %@, please download the latest version%@ from the App Store.", appName, versionText]
    : [NSString stringWithFormat:@"A new version of %@%@ is available on the App Store.", appName, versionText];

  UIAlertController *alert = [UIAlertController alertControllerWithTitle:customTitle ?: defaultTitle
                                                                 message:customMessage ?: defaultMessage
                                                          preferredStyle:UIAlertControllerStyleAlert];

  __weak typeof(self) weakSelf = self;
  UIAlertAction *updateAction = [UIAlertAction actionWithTitle:@"Update"
                                                         style:UIAlertActionStyleDefault
                                                       handler:^(__unused UIAlertAction *action) {
    weakSelf.requiredAlertVisible = NO;
    weakSelf.updateAlertVisible = NO;
    weakSelf.requiredUpdatePending = required;
    [weakSelf openStoreWithAppStoreID:appStoreID completion:^(__unused BOOL success, __unused NSError *error) {
      if (!success) {
        [weakSelf presentStoreOpenError:error required:required];
      }
    }];
  }];

  [alert addAction:updateAction];
  alert.preferredAction = updateAction;

  if (!required) {
    [alert addAction:[UIAlertAction actionWithTitle:@"Not now"
                                              style:UIAlertActionStyleCancel
                                            handler:^(__unused UIAlertAction *action) {
      weakSelf.updateAlertVisible = NO;
    }]];
  }

  self.requiredAlertVisible = required;
  self.updateAlertVisible = YES;
  [viewController presentViewController:alert animated:YES completion:completion];
}

- (void)presentStoreOpenError:(NSError *)error required:(BOOL)required
{
  dispatch_async(dispatch_get_main_queue(), ^{
    UIViewController *top = [self topViewController];
    if (!top) {
      self.updateAlertVisible = NO;
      NSLog(@"InAppUpdates could not open the App Store: %@",
            error.localizedDescription ?: @"Unknown error");
      return;
    }

    self.updateAlertVisible = YES;
    UIAlertController *alert =
      [UIAlertController alertControllerWithTitle:@"Unable to open the App Store"
                                          message:error.localizedDescription ?: @"Please try again later."
                                   preferredStyle:UIAlertControllerStyleAlert];
    [alert addAction:[UIAlertAction actionWithTitle:@"OK"
                                              style:UIAlertActionStyleDefault
                                            handler:^(__unused UIAlertAction *action) {
      self.updateAlertVisible = NO;
      if (required) {
        [self scheduleRequiredUpdatePrompt];
      }
    }]];
    [top presentViewController:alert animated:YES completion:nil];
  });
}

- (void)scheduleRequiredUpdatePrompt
{
  if (!self.requiredUpdatePending || self.requiredAlertVisible) {
    return;
  }

  dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC)),
                 dispatch_get_main_queue(), ^{
    if (!self.requiredUpdatePending || self.requiredAlertVisible ||
        UIApplication.sharedApplication.applicationState != UIApplicationStateActive) {
      return;
    }

    UIViewController *top = [self topViewController];
    if (!top || [top isKindOfClass:[UIAlertController class]]) {
      return;
    }

    [self presentUpdateAlertFrom:top
                         appName:self.requiredAppName ?: @"App"
                         version:self.requiredVersion ?: @""
                      appStoreID:self.requiredAppStoreID
                           title:self.customTitle
                         message:self.customMessage
                        required:YES
                      completion:nil];
  });
}

- (void)applicationDidBecomeActive:(__unused NSNotification *)notification
{
  [self scheduleRequiredUpdatePrompt];
}

- (instancetype)init
{
  self = [super init];
  if (self) {
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(applicationDidBecomeActive:)
                                                 name:UIApplicationDidBecomeActiveNotification
                                               object:nil];
  }
  return self;
}

- (void)dealloc
{
  [[NSNotificationCenter defaultCenter] removeObserver:self];
}

#pragma mark - App Store opening

- (void)openStoreWithAppStoreID:(NSString *)appStoreID
                     completion:(void (^)(BOOL success, NSError *error))completion
{
  NSString *urlString = [NSString stringWithFormat:@"https://apps.apple.com/app/id%@", appStoreID];
  NSURL *url = [NSURL URLWithString:urlString];

  if (!url) {
    NSError *error = [NSError errorWithDomain:@"InAppUpdates"
                                         code:1
                                     userInfo:@{NSLocalizedDescriptionKey: @"Invalid App Store URL."}];
    if (completion) completion(NO, error);
    return;
  }

  dispatch_async(dispatch_get_main_queue(), ^{
    [[UIApplication sharedApplication] openURL:url
                                        options:@{}
                              completionHandler:^(BOOL success) {
      NSError *error = nil;
      if (!success) {
        error = [NSError errorWithDomain:@"InAppUpdates"
                                    code:2
                                userInfo:@{NSLocalizedDescriptionKey: @"iOS could not open the App Store URL."}];
      }
      if (completion) completion(success, error);
    }];
  });
}

#pragma mark - View controller lookup

- (UIViewController *)topViewController
{
  __block UIWindow *keyWindow = nil;

  if (@available(iOS 13.0, *)) {
    for (UIScene *scene in [UIApplication sharedApplication].connectedScenes) {
      if (![scene isKindOfClass:[UIWindowScene class]] ||
          scene.activationState != UISceneActivationStateForegroundActive) {
        continue;
      }

      UIWindowScene *windowScene = (UIWindowScene *)scene;
      for (UIWindow *window in windowScene.windows) {
        if (window.isKeyWindow) {
          keyWindow = window;
          break;
        }
      }
      if (keyWindow) break;
    }
  } else {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    keyWindow = [UIApplication sharedApplication].keyWindow;
#pragma clang diagnostic pop
  }

  UIViewController *top = keyWindow.rootViewController;
  while (top.presentedViewController && !top.presentedViewController.isBeingDismissed) {
    top = top.presentedViewController;
  }
  return top;
}

#pragma mark - Version helpers

- (NSString *)nonEmptyStringForKey:(NSString *)key configuration:(NSDictionary *)configuration
{
  id value = configuration[key];
  if (![value isKindOfClass:[NSString class]]) {
    return nil;
  }

  NSString *string = (NSString *)value;
  return [[string stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] length] > 0
    ? string
    : nil;
}

- (BOOL)isValidAppStoreID:(NSString *)appStoreID
{
  NSPredicate *predicate = [NSPredicate predicateWithFormat:@"SELF MATCHES %@", @"^[0-9]+$"];
  return [predicate evaluateWithObject:appStoreID];
}

- (BOOL)isValidVersion:(NSString *)version
{
  if (version.length == 0) return NO;
  NSPredicate *predicate = [NSPredicate predicateWithFormat:@"SELF MATCHES %@", @"^[0-9]+(\\.[0-9]+)*$"];
  return [predicate evaluateWithObject:version];
}

- (NSComparisonResult)compareVersion:(NSString *)versionA to:(NSString *)versionB
{
  NSArray<NSString *> *partsA = [versionA componentsSeparatedByString:@"."];
  NSArray<NSString *> *partsB = [versionB componentsSeparatedByString:@"."];
  NSUInteger count = MAX(partsA.count, partsB.count);

  for (NSUInteger i = 0; i < count; i++) {
    NSDecimalNumber *a = i < partsA.count ? [NSDecimalNumber decimalNumberWithString:partsA[i]] : [NSDecimalNumber zero];
    NSDecimalNumber *b = i < partsB.count ? [NSDecimalNumber decimalNumberWithString:partsB[i]] : [NSDecimalNumber zero];
    NSComparisonResult result = [a compare:b];
    if (result != NSOrderedSame) return result;
  }
  return NSOrderedSame;
}

@end