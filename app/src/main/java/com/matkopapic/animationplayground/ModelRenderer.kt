package com.matkopapic.animationplayground

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.view.Choreographer
import android.view.TextureView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.KTX1Loader
import java.nio.ByteBuffer


class ModelRenderer {
    private lateinit var textureView: TextureView
    private lateinit var lifecycle: Lifecycle

    private lateinit var choreographer: Choreographer
    private lateinit var uiHelper: UiHelper

    private lateinit var modelViewer: MyModelViewer
    private lateinit var assets: AssetManager

    private val frameScheduler = FrameCallback()

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            choreographer.postFrameCallback(frameScheduler)
        }

        override fun onPause(owner: LifecycleOwner) {
            choreographer.removeFrameCallback(frameScheduler)
        }

        override fun onDestroy(owner: LifecycleOwner) {
            choreographer.removeFrameCallback(frameScheduler)
            lifecycle.removeObserver(this)
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    fun onSurfaceAvailable(textureView: TextureView, lifecycle: Lifecycle) {
        this.textureView = textureView
        this.lifecycle = lifecycle
        assets = this.textureView.context.assets

        lifecycle.addObserver(lifecycleObserver)
        choreographer = Choreographer.getInstance()
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            // This is needed to make the background transparent
            isOpaque = false
        }

        modelViewer = MyModelViewer(
            textureView = this.textureView,
            uiHelper = uiHelper,
        )

        // This is the other code needed to make the background transparent
//        modelViewer.scene.skybox = null
        modelViewer.view.blendMode = View.BlendMode.TRANSLUCENT
        modelViewer.renderer.clearOptions = modelViewer.renderer.clearOptions.apply {
            clear = true
        }

        createLights()

        // This part defines the quality of your model, feel free to change it or
        // add other options
        modelViewer.view.apply {
            renderQuality = renderQuality.apply {
                hdrColorBuffer = View.QualityLevel.MEDIUM
            }
        }

        this.textureView.setOnTouchListener{ _, event ->
            modelViewer.onTouchEvent(event)
            true
        }

        createRenderables()
    }

    private fun createRenderables() {
        val buffer = readAsset("models/Coin1.glb")
        modelViewer.loadModelGlb(buffer)
        modelViewer.transformToUnitCube()
    }

    private fun createLights() {
        val engine = modelViewer.engine
        val em = EntityManager.get()

        val light1 = em.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .direction(1.0f, 0.0f, -1.0f)
            .color(1f,1f,1f)
            .intensity(110_000.0f)
            .build(engine, light1)
        modelViewer.scene.addEntity(light1)

        val iblBuffer: ByteBuffer = readAsset("envs/lightroom_14b_ibl.ktx")
        val ibl: IndirectLight = KTX1Loader.createIndirectLight(engine, iblBuffer)
        ibl.intensity = 30000.0f
        modelViewer.scene.setIndirectLight(ibl)
    }

    private fun readAsset(assetName: String): ByteBuffer {
        val input = assets.open(assetName)
        val bytes = ByteArray(input.available())
        input.read(bytes)
        return ByteBuffer.wrap(bytes)
    }

    inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            modelViewer.render(frameTimeNanos)
        }
    }
}