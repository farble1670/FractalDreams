package org.jtb.fractaldreams

import android.content.Context
import kotlinx.coroutines.CoroutineScope

class GLJuliaDreamService : GLFractalDreamService() {
  override fun createRenderer(
    context: Context,
    fpsDisplay: FpsDisplay,
    scope: CoroutineScope
  ): GLFractalRenderer {
    return GLJuliaRenderer(context, fpsDisplay, scope)
  }
}
