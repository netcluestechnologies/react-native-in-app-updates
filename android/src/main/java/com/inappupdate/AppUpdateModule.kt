package com.inappupdate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.Log
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.ActivityResult as PlayActivityResult
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

class AppUpdateModule(
  private val context: ReactApplicationContext,
) : ReactContextBaseJavaModule(context), LifecycleEventListener, ActivityEventListener {
  private val updateManager: AppUpdateManager = AppUpdateManagerFactory.create(context)
  private val stateListener = InstallStateUpdatedListener(::onInstallStateUpdated)
  private val lock = Any()

  @Volatile private var updateFlowInProgress = false
  @Volatile private var updateCheckInProgress = false
  @Volatile private var flexibleListenerRegistered = false
  @Volatile private var flexibleUpdateDownloaded = false
  @Volatile private var mandatoryImmediateUpdatePending = false
  @Volatile private var mandatoryUpdateNeedsResumeCheck = false
  @Volatile private var closingForMandatoryUpdate = false
  private var shouldCheckUpdateOnResume = true
  @Volatile private var nativeDialogVisible = false

  override fun getName(): String = "InAppUpdates"

  init {
    context.addLifecycleEventListener(this)
    context.addActivityEventListener(this)
  }

  @ReactMethod
  fun checkVersion(flow: String, promise: Promise) {
    val type = when (flow) {
      "Y" -> AppUpdateType.IMMEDIATE
      "N" -> AppUpdateType.FLEXIBLE
      else -> {
        promise.reject("E_INVALID_UPDATE_TYPE", "Update type must be Y or N.")
        return
      }
    }

    synchronized(lock) {
      if (updateFlowInProgress || updateCheckInProgress) {
        promise.resolve(result("ALREADY_IN_PROGRESS"))
        return
      }
      updateCheckInProgress = true
    }

    updateManager.appUpdateInfo
      .addOnSuccessListener { info ->
        synchronized(lock) { updateCheckInProgress = false }
        when {
          type == AppUpdateType.FLEXIBLE && info.installStatus() == InstallStatus.DOWNLOADED -> {
            flexibleUpdateDownloaded = true
            showUpdateReadyDialog()
            emitState("DOWNLOADED")
            promise.resolve(result("DOWNLOADED"))
          }
          type == AppUpdateType.FLEXIBLE &&
            (info.installStatus() == InstallStatus.PENDING ||
              info.installStatus() == InstallStatus.DOWNLOADING ||
              info.installStatus() == InstallStatus.INSTALLING) -> {
            registerFlexibleListener()
            emitInstallState(info.installStatus(), info.bytesDownloaded(), info.totalBytesToDownload())
            promise.resolve(result("ALREADY_IN_PROGRESS"))
          }
          info.updateAvailability() ==
            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
            startFlow(info, AppUpdateType.IMMEDIATE, promise)
          }
          info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE -> {
            if (!info.isUpdateTypeAllowed(type)) {
              promise.resolve(result("UPDATE_TYPE_NOT_ALLOWED"))
            } else {
              if (type == AppUpdateType.FLEXIBLE) registerFlexibleListener()
              startFlow(info, type, promise)
            }
          }
          info.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE ->
            promise.resolve(result("NOT_AVAILABLE"))
          else -> {
            Log.w(TAG, "Unexpected update availability: ${info.updateAvailability()}")
            promise.reject(
              "E_UNEXPECTED_UPDATE_STATUS",
              "Google Play returned an unsupported update availability status.",
            )
          }
        }
      }
      .addOnFailureListener { error ->
        synchronized(lock) { updateCheckInProgress = false }
        Log.e(TAG, "Could not query Google Play for an app update.", error)
        promise.reject("E_UPDATE_CHECK_FAILED", error.message, error)
      }
  }

  @ReactMethod
  fun completeFlexibleUpdate(promise: Promise) {
    if (!flexibleUpdateDownloaded) {
      promise.reject(
        "E_UPDATE_NOT_DOWNLOADED",
        "There is no downloaded flexible update ready to install.",
      )
      return
    }

    updateManager.completeUpdate()
      .addOnSuccessListener {
        flexibleUpdateDownloaded = false
        emitState("INSTALLED")
        promise.resolve(result("INSTALLED"))
      }
      .addOnFailureListener { error ->
        Log.e(TAG, "Could not complete the flexible app update.", error)
        showInstallFailedDialog()
        promise.reject("E_UPDATE_INSTALL_FAILED", error.message, error)
      }
  }

  override fun onHostResume() {
    val shouldCheckUpdate = synchronized(lock) {
      if (!shouldCheckUpdateOnResume) return
      shouldCheckUpdateOnResume = false
      true
    }

    if (!shouldCheckUpdate) return

    updateManager.appUpdateInfo
      .addOnSuccessListener { info ->
        val verifyInterruptedMandatoryUpdate = synchronized(lock) {
          mandatoryImmediateUpdatePending && mandatoryUpdateNeedsResumeCheck
        }
        when {
          info.updateAvailability() ==
            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS -> {
            synchronized(lock) {
              updateFlowInProgress = false
              mandatoryImmediateUpdatePending = true
              mandatoryUpdateNeedsResumeCheck = false
            }
            getCurrentActivity()?.let { activity ->
              startFlow(info, AppUpdateType.IMMEDIATE, null, activity)
            }
          }
          info.installStatus() == InstallStatus.DOWNLOADED -> {
            flexibleUpdateDownloaded = true
            showUpdateReadyDialog()
            emitState("DOWNLOADED")
          }
          info.updateAvailability() == UpdateAvailability.UPDATE_NOT_AVAILABLE -> {
            synchronized(lock) {
              mandatoryImmediateUpdatePending = false
              mandatoryUpdateNeedsResumeCheck = false
              updateFlowInProgress = false
            }
          }
          verifyInterruptedMandatoryUpdate -> {
            emitState("CANCELLED")
            closeForMandatoryUpdate(getCurrentActivity())
          }
          info.installStatus() == InstallStatus.PENDING ||
            info.installStatus() == InstallStatus.DOWNLOADING ||
            info.installStatus() == InstallStatus.INSTALLING -> {
            registerFlexibleListener()
            emitInstallState(info.installStatus(), info.bytesDownloaded(), info.totalBytesToDownload())
          }
        }
      }
      .addOnFailureListener { error ->
        Log.w(TAG, "Could not restore the in-progress Play update state.", error)
        val mustClose = synchronized(lock) {
          mandatoryImmediateUpdatePending && mandatoryUpdateNeedsResumeCheck
        }
        if (mustClose) {
          emitState("FAILED")
          closeForMandatoryUpdate(getCurrentActivity())
        }
      }
  }

  override fun onHostPause() {
    synchronized(lock) {
      shouldCheckUpdateOnResume = true
      if (mandatoryImmediateUpdatePending) {
        mandatoryUpdateNeedsResumeCheck = true
      }
    }
  }

  override fun onHostDestroy() {
    updateFlowInProgress = false
  }

  override fun onActivityResult(
    activity: Activity,
    requestCode: Int,
    resultCode: Int,
    data: Intent?,
  ) {
    if (requestCode != UPDATE_REQUEST_CODE) return

    updateFlowInProgress = false
    when (resultCode) {
      Activity.RESULT_OK -> emitState("ACCEPTED")
      Activity.RESULT_CANCELED -> {
        emitState("CANCELLED")
        closeForMandatoryUpdate(activity)
      }
      PlayActivityResult.RESULT_IN_APP_UPDATE_FAILED -> {
        val errorCode = data?.getIntExtra("error.code", -1) ?: -1
        Log.e(TAG, "Google Play update flow failed with error=$errorCode")
        emitState("FAILED", errorCode)
        showUpdateFailedDialog()
        closeForMandatoryUpdate(activity)
      }
      else -> {
        val errorCode = data?.getIntExtra("error.code", -1) ?: -1
        Log.e(TAG, "Google Play update flow failed with result=$resultCode, error=$errorCode")
        emitState("FAILED", errorCode)
        showUpdateFailedDialog()
        closeForMandatoryUpdate(activity)
      }
    }
  }

  override fun onNewIntent(intent: Intent) = Unit

  override fun invalidate() {
    unregisterFlexibleListener()
    context.removeLifecycleEventListener(this)
    context.removeActivityEventListener(this)
    super.invalidate()
  }

  private fun startFlow(
    info: AppUpdateInfo,
    type: Int,
    promise: Promise?,
    activity: Activity? = getCurrentActivity(),
  ) {
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
      promise?.reject("E_NO_ACTIVITY", "No active Android activity is available for the update flow.")
      return
    }

    synchronized(lock) {
      if (updateFlowInProgress) {
        promise?.resolve(result("ALREADY_IN_PROGRESS"))
        return
      }
      updateFlowInProgress = true
      if (type == AppUpdateType.IMMEDIATE) {
        mandatoryImmediateUpdatePending = true
        mandatoryUpdateNeedsResumeCheck = false
        closingForMandatoryUpdate = false
      }
    }

    try {
      val options = AppUpdateOptions.newBuilder(type).build()
      val started = updateManager.startUpdateFlowForResult(info, activity, options, UPDATE_REQUEST_CODE)
      if (!started) {
        synchronized(lock) {
          updateFlowInProgress = false
          if (type == AppUpdateType.IMMEDIATE) {
            mandatoryImmediateUpdatePending = false
            mandatoryUpdateNeedsResumeCheck = false
          }
        }
        if (type == AppUpdateType.FLEXIBLE) unregisterFlexibleListener()
        emitState("NOT_STARTED", type)
        promise?.resolve(result("NOT_STARTED"))
        return
      }
      emitState("STARTED", type)
      promise?.resolve(result("STARTED"))
    } catch (error: Exception) {
      synchronized(lock) {
        updateFlowInProgress = false
        if (type == AppUpdateType.IMMEDIATE) {
          mandatoryImmediateUpdatePending = false
          mandatoryUpdateNeedsResumeCheck = false
        }
      }
      Log.e(TAG, "Could not launch the Google Play update flow.", error)
      promise?.reject("E_UPDATE_FLOW_START_FAILED", error.message, error)
    }
  }

  private fun closeForMandatoryUpdate(activity: Activity?) {
    val shouldClose = synchronized(lock) {
      if (!mandatoryImmediateUpdatePending || closingForMandatoryUpdate) {
        false
      } else {
        closingForMandatoryUpdate = true
        mandatoryImmediateUpdatePending = false
        mandatoryUpdateNeedsResumeCheck = false
        updateFlowInProgress = false
        true
      }
    }

    if (!shouldClose) return

    if (activity == null) {
      Log.e(TAG, "Cannot close the app after a mandatory update cancellation: no active Activity.")
      return
    }

    activity.runOnUiThread {
      if (!activity.isFinishing && !activity.isDestroyed) {
        activity.finish()
      }
    }
  }

  private fun showUpdateReadyDialog() {
    val activity = getCurrentActivity() ?: return
    activity.runOnUiThread {
      if (activity.isFinishing || activity.isDestroyed || nativeDialogVisible) return@runOnUiThread

      nativeDialogVisible = true
      AlertDialog.Builder(activity)
        .setTitle("Update ready")
        .setMessage("The update has downloaded. Restart the app to install it.")
        .setCancelable(false)
        .setPositiveButton("Restart now") { dialog, _ ->
          nativeDialogVisible = false
          dialog.dismiss()
          completeUpdateFromNative()
        }
        .setNegativeButton("Later") { dialog, _ ->
          nativeDialogVisible = false
          dialog.dismiss()
        }
        .setOnDismissListener { nativeDialogVisible = false }
        .show()
    }
  }

  private fun showUpdateFailedDialog() {
    val activity = getCurrentActivity() ?: return
    activity.runOnUiThread {
      if (activity.isFinishing || activity.isDestroyed || nativeDialogVisible) return@runOnUiThread

      nativeDialogVisible = true
      AlertDialog.Builder(activity)
        .setTitle("Update failed")
        .setMessage("The app could not download or install the update. Please try again later.")
        .setCancelable(true)
        .setPositiveButton("OK", null)
        .setOnDismissListener { nativeDialogVisible = false }
        .show()
    }
  }

  private fun completeUpdateFromNative() {
    if (!flexibleUpdateDownloaded) {
      showInstallFailedDialog()
      return
    }

    updateManager.completeUpdate()
      .addOnSuccessListener {
        flexibleUpdateDownloaded = false
        emitState("INSTALLED")
      }
      .addOnFailureListener { error ->
        Log.e(TAG, "Could not complete the flexible app update.", error)
        showInstallFailedDialog()
      }
  }

  private fun showInstallFailedDialog() {
    val activity = getCurrentActivity() ?: return
    activity.runOnUiThread {
      if (activity.isFinishing || activity.isDestroyed || nativeDialogVisible) return@runOnUiThread

      nativeDialogVisible = true
      AlertDialog.Builder(activity)
        .setTitle("Update could not be installed")
        .setMessage("Please try again later or update the app from Google Play.")
        .setCancelable(true)
        .setPositiveButton("OK", null)
        .setOnDismissListener { nativeDialogVisible = false }
        .show()
    }
  }

  private fun registerFlexibleListener() {
    if (!flexibleListenerRegistered) {
      updateManager.registerListener(stateListener)
      flexibleListenerRegistered = true
    }
  }

  private fun unregisterFlexibleListener() {
    if (flexibleListenerRegistered) {
      updateManager.unregisterListener(stateListener)
      flexibleListenerRegistered = false
    }
  }

  private fun onInstallStateUpdated(state: com.google.android.play.core.install.InstallState) {
    emitInstallState(
      state.installStatus(),
      state.bytesDownloaded(),
      state.totalBytesToDownload(),
      state.installErrorCode(),
    )

    when (state.installStatus()) {
      InstallStatus.DOWNLOADED -> {
        flexibleUpdateDownloaded = true
        unregisterFlexibleListener()
        showUpdateReadyDialog()
        emitState("DOWNLOADED")
      }
      InstallStatus.FAILED -> {
        updateFlowInProgress = false
        unregisterFlexibleListener()
        showUpdateFailedDialog()
      }
      InstallStatus.CANCELED -> {
        updateFlowInProgress = false
        unregisterFlexibleListener()
      }
    }
  }

  private fun emitInstallState(
    status: Int,
    bytesDownloaded: Long,
    totalBytesToDownload: Long,
    errorCode: Int = 0,
  ) {
    val name = when (status) {
      InstallStatus.PENDING -> "PENDING"
      InstallStatus.DOWNLOADING -> "DOWNLOADING"
      InstallStatus.DOWNLOADED -> "DOWNLOADED"
      InstallStatus.INSTALLING -> "INSTALLING"
      InstallStatus.INSTALLED -> "INSTALLED"
      InstallStatus.FAILED -> "FAILED"
      InstallStatus.CANCELED -> "CANCELLED"
      else -> "UNKNOWN"
    }
    val payload = Arguments.createMap().apply {
      putString("status", name)
      putDouble("bytesDownloaded", bytesDownloaded.toDouble())
      putDouble("totalBytesToDownload", totalBytesToDownload.toDouble())
      if (totalBytesToDownload > 0) {
        putDouble("progress", bytesDownloaded.toDouble() / totalBytesToDownload.toDouble())
      }
      putInt("errorCode", errorCode)
    }
    emit(payload)
  }

  private fun emitState(status: String, value: Int? = null) {
    val payload = Arguments.createMap().apply {
      putString("status", status)
      value?.let { putInt("value", it) }
    }
    emit(payload)
  }

  private fun emit(payload: com.facebook.react.bridge.WritableMap) {
    if (context.hasActiveReactInstance()) {
      context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit(EVENT_NAME, payload)
    }
  }

  private fun result(status: String) = Arguments.createMap().apply {
    putString("status", status)
  }

  companion object {
    private const val TAG = "InAppUpdates"
    private const val EVENT_NAME = "inAppUpdateState"
    private const val UPDATE_REQUEST_CODE = 7019
  }
}