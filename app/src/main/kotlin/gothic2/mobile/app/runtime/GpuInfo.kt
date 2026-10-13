package gothic2.mobile.app.runtime

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/** Имя GPU (GL_RENDERER) через временный внеэкранный EGL-контекст — для выбора профиля. */
object GpuInfo {
    fun rendererName(): String = runCatching { query() }.getOrNull() ?: "unknown"

    private fun query(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                EGL14.eglMakeCurrent(display, surface, surface, ctx)
                return GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }
}
