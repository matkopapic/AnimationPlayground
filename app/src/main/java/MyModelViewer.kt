package com.matkopapic.animationplayground

import android.opengl.Matrix
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.Fence
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Animator
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.MaterialProvider
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.max
import com.google.android.filament.utils.scale
import com.google.android.filament.utils.translation
import com.google.android.filament.utils.transpose
import com.matkopapic.animationplayground.com.matkopapic.animationplayground.MyGestureDetector
import kotlinx.coroutines.Job
import java.nio.Buffer
import kotlin.math.abs

private const val kNearPlane = 0.05f     // 5 cm
private const val kFarPlane = 1000.0f    // 1 km
private const val kAperture = 16f
private const val kShutterSpeed = 1f / 125f
private const val kSensitivity = 100f
private const val defaultVelocity = 50f

class MyModelViewer(
    val engine: Engine,
    private val uiHelper: UiHelper
) : android.view.View.OnTouchListener {
    var asset: FilamentAsset? = null
        private set

    var animator: Animator? = null
        private set

    @Suppress("unused")
    val progress
        get() = resourceLoader.asyncGetLoadProgress()

    var normalizeSkinningWeights = true

    var cameraFocalLength = 28f
        set(value) {
            field = value
            updateCameraProjection()
        }

    var cameraNear = kNearPlane
        set(value) {
            field = value
            updateCameraProjection()
        }

    var cameraFar = kFarPlane
        set(value) {
            field = value
            updateCameraProjection()
        }

    val scene: Scene
    val view: View
    val camera: Camera
    val renderer: Renderer
    @Entity
    val light: Int

    private lateinit var displayHelper: DisplayHelper
    private lateinit var cameraManipulator: Manipulator
    private lateinit var gestureDetector: MyGestureDetector
    private var surfaceView: SurfaceView? = null
    private var textureView: TextureView? = null

    private var fetchResourcesJob: Job? = null

    private var swapChain: SwapChain? = null
    private var assetLoader: AssetLoader
    private var materialProvider: MaterialProvider
    private var resourceLoader: ResourceLoader
    private val readyRenderables = IntArray(128) // add up to 128 entities at a time

    private val eyePos = arrayOf(0.0, 0.0, 6.0).toDoubleArray()
    private val target = arrayOf(0.0, 0.0, 0.0).toDoubleArray()
    private val upward = arrayOf(0.0, 1.0, 0.0).toDoubleArray()

    private var coinAngle = 0f
    private var coinAngularVelocity = defaultVelocity
    private val friction = 0.98f // slow down over time

    init {
        renderer = engine.createRenderer()
        scene = engine.createScene()
        camera = engine.createCamera(engine.entityManager.create()).apply {
            setExposure(kAperture, kShutterSpeed, kSensitivity)
        }
        view = engine.createView()
        view.scene = scene
        view.camera = camera

        materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine, normalizeSkinningWeights)

        // Always add a direct light source since it is required for shadowing.
        // We highly recommend adding an indirect light as well.

        light = EntityManager.get().create()

        val (r, g, b) = Colors.cct(6_500.0f)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(r, g, b)
            .intensity(100_000.0f)
            .direction(0.0f, -1.0f, 0.0f)
            .castShadows(true)
            .build(engine, light)

        scene.addEntity(light)
    }

//    constructor(
//        surfaceView: SurfaceView,
//        engine: Engine = Engine.create(),
//        uiHelper: UiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
//        manipulator: Manipulator? = null
//    ) : this(engine, uiHelper) {
//        cameraManipulator = manipulator ?: Manipulator.Builder()
//            .targetPosition(kDefaultObjectPosition.x, kDefaultObjectPosition.y, kDefaultObjectPosition.z)
//            .viewport(surfaceView.width, surfaceView.height)
//            .build(Manipulator.Mode.ORBIT)
//
//        this.surfaceView = surfaceView
//        gestureDetector = GestureDetector(surfaceView.context, object : SimpleOnGestureListener(){
//            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
//                // Convert horizontal fling velocity into angular velocity
//                val spinBoost = velocityX / 50f  // adjust scaling as needed
//                coinAngularVelocity += spinBoost
//                return true
//            }
//        })
//        displayHelper = DisplayHelper(surfaceView.context)
//        uiHelper.renderCallback = SurfaceCallback()
//        uiHelper.attachTo(surfaceView)
//        addDetachListener(surfaceView)
//    }

    constructor(
        textureView: TextureView,
        engine: Engine = Engine.create(),
        uiHelper: UiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
        manipulator: Manipulator? = null
    ) : this(engine, uiHelper) {
        cameraManipulator = manipulator ?: Manipulator.Builder()
            .targetPosition(target[0].toFloat(), target[1].toFloat(), target[2].toFloat())
            .orbitHomePosition(eyePos[0].toFloat(), eyePos[1].toFloat(), eyePos[2].toFloat())
            .viewport(textureView.width, textureView.height)
            .build(Manipulator.Mode.ORBIT)

        this.textureView = textureView
//        gestureDetector = GestureDetector(textureView.context, object : SimpleOnGestureListener(){
//
//
//            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
//                // Convert horizontal fling velocity into angular velocity
//                val spinBoost = velocityX / 50f  // adjust scaling as needed
//                coinAngularVelocity += spinBoost
//                return true
//            }
//        })
        gestureDetector = MyGestureDetector(textureView, cameraManipulator)
        displayHelper = DisplayHelper(textureView.context)
        uiHelper.renderCallback = SurfaceCallback()
        uiHelper.attachTo(textureView)
        addDetachListener(textureView)
    }

    /**
     * Loads a monolithic binary glTF and populates the Filament scene.
     */
    fun loadModelGlb(buffer: Buffer) {
        destroyModel()
        asset = assetLoader.createAsset(buffer)
        asset?.let { asset ->
            resourceLoader.asyncBeginLoad(asset)
            animator = asset.getInstance().animator
            asset.releaseSourceData()
        }
    }

    /**
     * Sets up a root transform on the current model to make it fit into a unit cube.
     *
     * @param centerPoint Coordinate of center point of unit cube, defaults to < 0, 0, -4 >
     */
    fun transformToUnitCube(centerPoint: Float3 = kDefaultObjectPosition) {
        asset?.let { asset ->
            val tm = engine.transformManager
            var center = asset.boundingBox.center.let { v -> Float3(v[0], v[1], v[2]) }
            val halfExtent = asset.boundingBox.halfExtent.let { v -> Float3(v[0], v[1], v[2]) }
            val maxExtent = 2.0f * max(halfExtent)
            val scaleFactor = 2.0f / maxExtent
            center -= centerPoint / scaleFactor
            val transform = scale(Float3(scaleFactor)) * translation(-center)
            tm.setTransform(tm.getInstance(asset.root), transpose(transform).toFloatArray())
        }
    }

    /**
     * Frees all entities associated with the most recently-loaded model.
     */
    fun destroyModel() {
        fetchResourcesJob?.cancel()
        resourceLoader.asyncCancelLoad()
        resourceLoader.evictResourceData()
        asset?.let { asset ->
            this.scene.removeEntities(asset.entities)
            assetLoader.destroyAsset(asset)
            this.asset = null
            this.animator = null
        }
    }

    private var lastTime = System.nanoTime()
    /**
     * Renders the model and updates the Filament camera.
     *
     * @param frameTimeNanos time in nanoseconds when the frame started being rendered,
     *                       typically comes from {@link android.view.Choreographer.FrameCallback}
     */
    fun render(frameTimeNanos: Long) {
        if (!uiHelper.isReadyToRender) {
            return
        }

        // Allow the resource loader to finalize textures that have become ready.
        resourceLoader.asyncUpdateLoad()

        // Add renderable entities to the scene as they become ready.
        asset?.let { populateScene(it) }
        cameraManipulator.getLookAt(eyePos, target, upward)
        camera.lookAt(
            eyePos[0], eyePos[1], eyePos[2],
            target[0], target[1], target[2],
            upward[0], upward[1], upward[2])

        val deltaTime = (frameTimeNanos - lastTime) / 1_000_000_000f
        lastTime = frameTimeNanos

        updateCoinRotation(deltaTime)

        // Render the scene, unless the renderer wants to skip the frame.
        if (renderer.beginFrame(swapChain!!, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun populateScene(asset: FilamentAsset) {
        val rcm = engine.renderableManager
        var count = 0
        val popRenderables = { count = asset.popRenderables(readyRenderables); count != 0 }
        while (popRenderables()) {
            for (i in 0..count - 1) {
                val ri = rcm.getInstance(readyRenderables[i])
                rcm.setScreenSpaceContactShadows(ri, true)
            }
            scene.addEntities(readyRenderables.take(count).toIntArray())
        }
        scene.addEntities(asset.lightEntities)
    }

    private fun addDetachListener(view: android.view.View) {
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {}
            override fun onViewDetachedFromWindow(v: android.view.View) {
                uiHelper.detach()

                destroyModel()
                assetLoader.destroy()
                materialProvider.destroyMaterials()
                materialProvider.destroy()
                resourceLoader.destroy()

                engine.destroyEntity(light)
                engine.destroyRenderer(renderer)
                engine.destroyView(this@MyModelViewer.view)
                engine.destroyScene(scene)
                engine.destroyCameraComponent(camera.entity)
                EntityManager.get().destroy(camera.entity)

                EntityManager.get().destroy(light)

                engine.destroy()
            }
        })
    }

    /**
     * Handles a [MotionEvent] to enable one-finger orbit, two-finger pan, and pinch-to-zoom.
     */
    fun onTouchEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> coinAngularVelocity = 0f

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> coinAngularVelocity = defaultVelocity
        }
        gestureDetector.onTouchEvent(event)
    }

    private val transform = FloatArray(16)

    private fun updateCoinRotation(deltaTime: Float) {
        // Apply friction
        val newVelocity = coinAngularVelocity * friction
        if (abs(newVelocity) > defaultVelocity) {
            coinAngularVelocity = newVelocity
        }

        // Update angle
        if (abs(coinAngle) > 360f) {
            coinAngle = 0f
        }
        coinAngle += coinAngularVelocity * deltaTime

        asset?.let {
            Matrix.setRotateM(transform, 0, coinAngle, 0f, 1f, 0f)

            engine.transformManager.setTransform(
                engine.transformManager.getInstance(it.root),
                transform
            )
        }
    }

    @SuppressWarnings("ClickableViewAccessibility")
    override fun onTouch(view: android.view.View, event: MotionEvent): Boolean {
        onTouchEvent(event)
        return true
    }

    private fun updateCameraProjection() {
        val width = view.viewport.width
        val height = view.viewport.height
        val aspect = width.toDouble() / height.toDouble()
        camera.setLensProjection(cameraFocalLength.toDouble(), aspect,
            cameraNear.toDouble(), cameraFar.toDouble())
    }

    inner class SurfaceCallback : UiHelper.RendererCallback {
        override fun onNativeWindowChanged(surface: Surface) {
            swapChain?.let { engine.destroySwapChain(it) }
            swapChain = engine.createSwapChain(surface)
            surfaceView?.let { displayHelper.attach(renderer, it.display) }
            textureView?.let { displayHelper.attach(renderer, it.display) }
        }

        override fun onDetachedFromSurface() {
            displayHelper.detach()
            swapChain?.let {
                engine.destroySwapChain(it)
                engine.flushAndWait()
                swapChain = null
            }
        }

        override fun onResized(width: Int, height: Int) {
            view.viewport = Viewport(0, 0, width, height)
            cameraManipulator.setViewport(width, height)
            updateCameraProjection()
            synchronizePendingFrames(engine)
        }
    }

    private fun synchronizePendingFrames(engine: Engine) {
        // Wait for all pending frames to be processed before returning. This is to
        // avoid a race between the surface being resized before pending frames are
        // rendered into it.
        val fence = engine.createFence()
        fence.wait(Fence.Mode.FLUSH, Fence.WAIT_FOR_EVER)
        engine.destroyFence(fence)
    }

    companion object {
        private val kDefaultObjectPosition = Float3(0.0f, 0.0f, -4.0f)
    }
}