package com.aimonitor.app

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep

class MainActivity : TauriActivity() {
  // WRY 0.55.1 通过 JNI 调用 activity.getId()/setId(int)。
  // JNI 的 GetMethodID 只查接收类自身声明的方法，不沿继承链查找，
  // 因此 MainActivity 必须自己声明 getId()/setId(int)。
  // 父类 WryActivity 的 id 需要在生成后被 patch 成 open，否则无法 override。
  // 注意：androidx.annotation.Keep 不支持 Kotlin PROPERTY target，
  // 写在 property 上只会落到 backing field，不会保住生成的 getter/setter。
  // 必须显式用 @get:Keep @set:Keep 让 R8 保留 getId()/setId(int)。
  @get:Keep
  @set:Keep
  override var id: Int = 0

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
  }
}
