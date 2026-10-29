package com.inappupdate

import com.facebook.react.bridge.ReactApplicationContext

class InAppUpdateModule(reactContext: ReactApplicationContext) :
  NativeInAppUpdateSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeInAppUpdateSpec.NAME
  }
}
