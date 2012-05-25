/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.util.Log;

/**
 * @hide This is only used by the browser
 */
public class VideoTextureView extends View {
    private static final String LOG_TAG = "VideoTextureView";

    private HardwareLayer mLayer;
    private SurfaceTexture mSurface;
    private int mTextureName;

    private boolean mOpaque = true;

    private final Matrix mMatrix = new Matrix();
    private boolean mMatrixChanged;

    private final Object[] mLock = new Object[0];
    private boolean mUpdateLayer;
    private boolean mSurfaceTextureReady;

    private SurfaceTexture.OnFrameAvailableListener mUpdateListener;

    private final Object[] mNativeWindowLock = new Object[0];
    // Used from native code, do not write!
    @SuppressWarnings({"UnusedDeclaration"})
    private int mNativeWindow;
    private VideoTextureListener mListener;
    private int mOrientation;

    /**
     * Creates a new VideoTextureView.
     *
     * @param context The context to associate this view with.
     */
    public VideoTextureView(Context context, SurfaceTexture surfaceTexture, int textureName, boolean surfaceTextureReady) {
        super(context);
        // Make sure we get a valid surface texture and texture name
        assert(surfaceTexture != null);
        assert(textureName > 0);
        mLayerPaint = new Paint();
        mSurface = surfaceTexture;
        mTextureName = textureName;
        mSurfaceTextureReady = surfaceTextureReady;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isOpaque() {
        return mOpaque;
    }

    /**
     * Indicates whether the content of this VideoTextureView is opaque. The
     * content is assumed to be opaque by default.
     *
     * @param opaque True if the content of this VideoTextureView is opaque,
     *               false otherwise
     */
    public void setOpaque(boolean opaque) {
        if (opaque != mOpaque) {
            mOpaque = opaque;
            updateLayer();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!isHardwareAccelerated()) {
            Log.w(LOG_TAG, "A VideoTextureView or a subclass can only be "
                    + "used with hardware acceleration enabled.");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        destroySurface();
    }

    private void destroySurface() {
        if (mLayer != null) {
            synchronized (mNativeWindowLock) {
                nDestroyNativeWindow();
            }

            mLayer.destroy();
            mSurface = null;
            mLayer = null;
        }
    }

    /**
     * The layer type of a VideoTextureView is ignored since a VideoTextureView is always
     * considered to act as a hardware layer. The optional paint supplied to this
     * method will however be taken into account when rendering the content of
     * this VideoTextureView.
     *
     * @param layerType The ype of layer to use with this view, must be one of
     *        {@link #LAYER_TYPE_NONE}, {@link #LAYER_TYPE_SOFTWARE} or
     *        {@link #LAYER_TYPE_HARDWARE}
     * @param paint The paint used to compose the layer. This argument is optional
     *        and can be null. It is ignored when the layer type is
     *        {@link #LAYER_TYPE_NONE}
     */
    @Override
    public void setLayerType(int layerType, Paint paint) {
        if (paint != mLayerPaint) {
            mLayerPaint = paint;
            invalidate();
        }
    }

    /**
     * Always returns {@link #LAYER_TYPE_HARDWARE}.
     */
    @Override
    public int getLayerType() {
        return LAYER_TYPE_HARDWARE;
    }

    @Override
    boolean hasStaticLayer() {
        return true;
    }

    /**
     * Subclasses of VideoTextureView cannot do their own rendering
     * with the {@link Canvas} object.
     *
     * @param canvas The Canvas to which the View is rendered.
     */
    @Override
    public final void draw(Canvas canvas) {
        applyUpdate();
        applyTransformMatrix();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        if (mOrientation != newConfig.orientation) {
            mOrientation = newConfig.orientation;
            updateLayer();
        }
    }

    @Override
    boolean destroyLayer() {
        return false;
    }

    /**
     * @hide
     */
    @Override
    protected void destroyHardwareResources() {
        super.destroyHardwareResources();
        destroySurface();
        invalidateParentCaches();
        invalidate(true);
    }

    @Override
    HardwareLayer getHardwareLayer() {
        if (mLayer == null) {
            if (mAttachInfo == null || mAttachInfo.mHardwareRenderer == null) {
                return null;
            }
            mLayer = mAttachInfo.mHardwareRenderer.createHardwareLayer(mOpaque, mTextureName);
            mAttachInfo.mHardwareRenderer.setSurfaceTexture(mLayer, mSurface);

            nSetDefaultBufferSize(mSurface, getWidth(), getHeight());
            nCreateNativeWindow(mSurface);

            mUpdateListener = new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    // Per SurfaceTexture's documentation, the callback may be invoked
                    // from an arbitrary thread
                    synchronized (mLock) {
                        mUpdateLayer = true;
                    }
                    postInvalidateDelayed(0);
                }
            };
            mSurface.setOnFrameAvailableListener(mUpdateListener);

            if (mSurfaceTextureReady) {
                // Force layer update after initialization
                updateLayer();
            }
        }

        applyUpdate();
        applyTransformMatrix();

        return mLayer;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        if (mLayer != null) {
            // When the view becomes invisible, stop updating it, it's a waste of CPU
            // To cancel updates, the easiest thing to do is simply to remove the
            // updates listener
            if (visibility == VISIBLE) {
                mSurface.setOnFrameAvailableListener(mUpdateListener);
                updateLayer();
            } else {
                mSurface.setOnFrameAvailableListener(null);
            }
        }
    }

    private void updateLayer() {
        mUpdateLayer = true;
        invalidate();
    }

    private void applyUpdate() {
        if (mLayer == null) {
            return;
        }

        synchronized (mLock) {
            if (mUpdateLayer) {
                mUpdateLayer = false;
            } else {
                return;
            }
        }

        mLayer.update(getWidth(), getHeight(), mOpaque);

        if (mListener != null) {
            mListener.onVideoTextureUpdated(mSurface);
        }
    }

    /**
     * <p>Sets the transform to associate with this texture view.
     * The specified transform applies to the underlying surface
     * texture and does not affect the size or position of the view
     * itself, only of its content.</p>
     *
     * <p>Some transforms might prevent the content from drawing
     * all the pixels contained within this view's bounds. In such
     * situations, make sure this texture view is not marked opaque.</p>
     *
     * @param transform The transform to apply to the content of
     *        this view.
     *
     * @see #getTransform(android.graphics.Matrix)
     * @see #isOpaque()
     * @see #setOpaque(boolean)
     */
    public void setTransform(Matrix transform) {
        mMatrix.set(transform);
        mMatrixChanged = true;
        invalidateParentIfNeeded();
    }

    /**
     * Returns the transform associated with this texture view.
     *
     * @param transform The {@link Matrix} in which to copy the current
     *        transform. Can be null.
     *
     * @return The specified matrix if not null or a new {@link Matrix}
     *         instance otherwise.
     *
     * @see #setTransform(android.graphics.Matrix)
     */
    public Matrix getTransform(Matrix transform) {
        if (transform == null) {
            transform = new Matrix();
        }

        transform.set(mMatrix);

        return transform;
    }

    private void applyTransformMatrix() {
        if (mMatrixChanged) {
            mLayer.setTransform(mMatrix);
            mMatrixChanged = false;
        }
    }

    /**
     * Returns the {@link VideoTextureListener} currently associated with this
     * texture view.
     *
     * @see #setVideoTextureListener(android.view.TextureView.VideoTextureListener)
     * @see VideoTextureListener
     */
    public VideoTextureListener getVideoTextureListener() {
        return mListener;
    }

    /**
     * Sets the {@link VideoTextureListener} used to listen to surface
     * texture events.
     *
     * @see #getVideoTextureListener()
     * @see VideoTextureListener
     */
    public void setVideoTextureListener(VideoTextureListener listener) {
        mListener = listener;
    }

    /**
     * This listener can be used to be notified when the surface texture
     * associated with this texture view is available.
     */
    public static interface VideoTextureListener {
       /**
         * Invoked when the specified {@link SurfaceTexture} is updated through
         * {@link SurfaceTexture#updateTexImage()}.
         *
         * @param surface The surface just updated
         */
        public void onVideoTextureUpdated(SurfaceTexture surface);
    }

    /**
     * Returns true if the {@link SurfaceTexture} associated with this
     * VideoTextureView is available for rendering. When this method returns
     * true, {@link #getSurfaceTexture()} returns a valid surface texture.
     */
    public boolean isAvailable() {
        return mLayer != null && mSurface != null;
    }

    /**
     * Returns the {@link SurfaceTexture} used by this view. This method
     * may return null if the view is not attached to a window or if the surface
     * texture has not been initialized yet.
     *
     * @see #isAvailable()
     */
    public SurfaceTexture getSurfaceTexture() {
        return mSurface;
    }

    private native void nCreateNativeWindow(SurfaceTexture surface);
    private native void nDestroyNativeWindow();
    private static native void nSetDefaultBufferSize(SurfaceTexture surfaceTexture,
            int width, int height);
}
