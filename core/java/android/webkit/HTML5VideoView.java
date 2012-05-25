/* Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.webkit;

import android.Manifest.permission;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.Metadata;
import android.net.Uri;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VideoTextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.MediaController.MediaPlayerControl;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import android.content.Intent;
/**
 * @hide This is only used by the browser
 */
public class HTML5VideoView implements MediaPlayer.OnPreparedListener,
    MediaPlayerControl, View.OnTouchListener, VideoTextureView.VideoTextureListener,
    SurfaceTexture.OnFrameAvailableListener, MediaPlayer.OnVideoSizeChangedListener
{
    private static final String LOGTAG = "HTML5VideoView";
    private static final String COOKIE = "Cookie";
    private static final String HIDE_URL_LOGS = "x-hide-urls-from-log";

    private static final long ANIMATION_DURATION = 750L; // in ms

    // For handling the seekTo before prepared, we need to know whether or not
    // the video is prepared. Therefore, we differentiate the state between
    // prepared and not prepared.
    // When the video is not prepared, we will have to save the seekTo time,
    // and use it when prepared to play.
    // NOTE: these values are in sync with VideoLayerAndroid.h in webkit side.
    // Please keep them in sync when changed.
    static final int STATE_INITIALIZED        = 0;
    static final int STATE_NOTPREPARED        = 1;
    static final int STATE_PREPARED           = 2;
    static final int STATE_PLAYING            = 3;
    static final int STATE_BUFFERING          = 4;
    static final int STATE_RELEASED           = 5;
    private int mCurrentState;

    static final int ANIMATION_STATE_NONE     = 0;
    static final int ANIMATION_STATE_STARTED  = 1;
    static final int ANIMATION_STATE_FINISHED = 2;
    private int mAnimationState;

    private HTML5VideoViewProxy mProxy;

    // Save the seek time when not prepared. This can happen when switching
    // video besides initial load.
    private int mSaveSeekTime;

    // This is used to find the VideoLayer on the native side.
    private int mVideoLayerId;

    private MediaPlayer mPlayer;

    // This will be set up every time we create the HTML5VideoView object.
    // Set to true if video should start upon MediaPlayer prepared.
    private boolean mAutoStart;

    // We need to save such info.
    private Uri mUri;
    private Map<String, String> mHeaders;

    // The timer for timeupate events.
    // See http://www.whatwg.org/specs/web-apps/current-work/#event-media-timeupdate
    private Timer mTimer;

    private boolean mIsFullscreen;

    // The spec says the timer should fire every 250 ms or less.
    private static final int TIMEUPDATE_PERIOD = 250;  // ms

    private boolean mPauseDuringPreparing;

    private int mFullscreenWidth;
    private int mFullscreenHeight;

    private float mInlineX;
    private float mInlineY;
    private float mInlineWidth;
    private float mInlineHeight;

    private Point mDisplaySize;

    // The Media Controller only used for full screen mode
    private MediaController mMediaController;

    // Data only for MediaController
    private boolean mCanSeekBack;
    private boolean mCanSeekForward;
    private boolean mCanPause;
    private int mCurrentBufferPercentage;

    // The progress view.
    private View mProgressView;
    // The container for the progress view and video view
    private FrameLayout mLayout;

    private SurfaceTexture mSurfaceTexture;
    private MyVideoTextureView mTextureView;
    private boolean mSurfaceTextureReady;

    // common Video control FUNCTIONS:
    public void start() {
        if (mCurrentState == STATE_PREPARED) {
            // When replaying the same video, there is no onPrepared call.
            // Therefore, the timer should be set up here.
            if (mTimer == null)
            {
                mTimer = new Timer();
                mTimer.schedule(new TimeupdateTask(mProxy), TIMEUPDATE_PERIOD,
                        TIMEUPDATE_PERIOD);
            }
            mPlayer.start();
            setPlayerBuffering(false);

            // Notify webkit MediaPlayer that video is playing to make sure
            // webkit MediaPlayer is always synchronized with the proxy.
            // This is particularly important when using the fullscreen
            // MediaController.
            mProxy.dispatchOnPlaying();
        } else
            mAutoStart = true;
    }

    public void pause() {
        if (isPlaying()) {
            mPlayer.pause();
        } else if (mCurrentState == STATE_NOTPREPARED) {
            mPauseDuringPreparing = true;
        }
        // Notify webkit MediaPlayer that video is paused to make sure
        // webkit MediaPlayer is always synchronized with the proxy
        // This is particularly important when using the fullscreen
        // MediaController.
        mProxy.dispatchOnPaused();

        // Delete the Timer to stop it since there is no stop call.
        if (mTimer != null) {
            mTimer.purge();
            mTimer.cancel();
            mTimer = null;
        }
    }

    public int getDuration() {
        if (mCurrentState == STATE_PREPARED) {
            return mPlayer.getDuration();
        } else {
            return -1;
        }
    }

    public int getCurrentPosition() {
        if (mCurrentState == STATE_PREPARED) {
            return mPlayer.getCurrentPosition();
        }
        return 0;
    }

    public void seekTo(int pos) {
        if (mCurrentState == STATE_PREPARED)
            mPlayer.seekTo(pos);
        else
            mSaveSeekTime = pos;
    }

    public boolean isPlaying() {
        if (mCurrentState == STATE_PREPARED) {
            return mPlayer.isPlaying();
        } else {
            return false;
        }
    }

    public void release() {
        if (mCurrentState != STATE_RELEASED) {
            stopPlayback();
            mPlayer.release();
        }
        mCurrentState = STATE_RELEASED;
    }

    public void stopPlayback() {
        if (mCurrentState == STATE_PREPARED) {
            mPlayer.stop();
        }
    }

    public void setVolume(float volume) {
        if (mCurrentState != STATE_RELEASED) {
            mPlayer.setVolume(volume, volume);
        }
    }

    // Every time we start a new Video, we create a VideoView and a MediaPlayer
    HTML5VideoView(HTML5VideoViewProxy proxy, int videoLayerId, int position, boolean autoStart) {
        mPlayer = new MediaPlayer();
        mCurrentState = STATE_INITIALIZED;
        mProxy = proxy;
        mVideoLayerId = videoLayerId;
        mSaveSeekTime = position;
        mAutoStart = autoStart;
        mTimer = null;
        mPauseDuringPreparing = false;
        mIsFullscreen = false;
        mSurfaceTextureReady = false;
        mDisplaySize = new Point();
    }

    private static Map<String, String> generateHeaders(String url,
            HTML5VideoViewProxy proxy) {
        boolean isPrivate = proxy.getWebView().isPrivateBrowsingEnabled();
        String cookieValue = CookieManager.getInstance().getCookie(url, isPrivate);
        Map<String, String> headers = new HashMap<String, String>();
        if (cookieValue != null) {
            headers.put(COOKIE, cookieValue);
        }
        if (isPrivate) {
            headers.put(HIDE_URL_LOGS, "true");
        }

        return headers;
    }

    public void setVideoURI(String uri) {
        mUri = Uri.parse(uri);
        mHeaders = generateHeaders(uri, mProxy);
    }

    // When there is a frame ready from surface texture, we should tell WebView
    // to refresh.
    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        // TODO: This should support partial invalidation too.
        mProxy.getWebView().invalidate();
        mSurfaceTextureReady = true;
    }

    public void retrieveMetadata(HTML5VideoViewProxy proxy) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(mUri.toString(), mHeaders);
            proxy.updateSizeAndDuration(
                Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),
                Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)),
                Integer.parseInt(retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)));
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (RuntimeException e) {
            // RuntimeException occurs when connection is not available or
            // the source type is not supported (e.g. HLS). Not calling
            // e.printStackTrace() here since it occurs quite often.
        } finally {
            retriever.release();
        }
    }

    // Listeners setup FUNCTIONS:
    public void setOnCompletionListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnCompletionListener(proxy);
    }

    public void prepareDataAndDisplayMode() {
        Intent i = new Intent("com.android.music.musicservicecommand");
        i.putExtra("command","pause");
        mProxy.getContext().sendBroadcast(i);
        decideDisplayMode();

        mPlayer.setOnCompletionListener(mProxy);
        mPlayer.setOnPreparedListener(this);
        mPlayer.setOnErrorListener(mProxy);
        mPlayer.setOnInfoListener(mProxy);
        mPlayer.setOnVideoSizeChangedListener(this);

        // When there is exception, we could just bail out silently.
        // No Video will be played though. Write the stack for debug
        try {
            mPlayer.setDataSource(mProxy.getContext(), mUri, mHeaders);
            mPlayer.prepareAsync();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCurrentState = STATE_NOTPREPARED;

        // TODO: This is a workaround, after b/5375681 fixed, we should switch
        // to the better way.
        if (mProxy.getContext().checkCallingOrSelfPermission(permission.WAKE_LOCK)
                == PackageManager.PERMISSION_GRANTED) {
            mPlayer.setWakeMode(mProxy.getContext(), PowerManager.FULL_WAKE_LOCK);
        }
        if (!mIsFullscreen)
            setInlineFrameAvailableListener();
    }

    // This configures the SurfaceTexture OnFrameAvailableListener in inline mode
    private void setInlineFrameAvailableListener() {
        getSurfaceTexture().setOnFrameAvailableListener(this);
    }

    public int getVideoLayerId() {
        return mVideoLayerId;
    }

    public int getCurrentState() {
        if (isPlaying()) {
            return STATE_PLAYING;
        } else {
            return mCurrentState;
        }
    }

    private final class TimeupdateTask extends TimerTask {
        private HTML5VideoViewProxy mProxy;

        public TimeupdateTask(HTML5VideoViewProxy proxy) {
            mProxy = proxy;
        }

        @Override
        public void run() {
            mProxy.onTimeupdate();
        }
    }

    public void onPrepared(MediaPlayer mp) {
        mCurrentState = STATE_PREPARED;
        seekTo(mSaveSeekTime);

        if (mProxy != null)
            mProxy.onPrepared(mp);

        if (mPauseDuringPreparing || !mAutoStart)
            mPauseDuringPreparing = false;
        else
            start();

        if (mIsFullscreen) {
            // attachMediaController is needed here for slow networks where the
            // fullscreen animation has finished before media is prepared.
            if (mAnimationState == ANIMATION_STATE_FINISHED)
                attachMediaController();
            if (mProgressView != null)
                mProgressView.setVisibility(View.GONE);
        }
    }

    public void decideDisplayMode() {
        SurfaceTexture surfaceTexture = getSurfaceTexture();
        Surface surface = new Surface(surfaceTexture);
        mPlayer.setSurface(surface);
        surface.release();
    }

    // SurfaceTexture will be created lazily here
    public SurfaceTexture getSurfaceTexture() {
        // Create the surface texture.
        if (mSurfaceTexture == null) {
            mSurfaceTexture = new SurfaceTexture(mProxy.getTextureName());
        }
        return mSurfaceTexture;
    }

    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
        if ((mProxy.getVideoWidth() != width || mProxy.getVideoHeight() != height)
            && mTextureView != null) {
            // Set visible if previously it was not visible due to width and height unknown
            // Request layout now that video width and height are known
            // This will trigger onMeasure to get the display size right
            mTextureView.requestLayout();
        }
        if (mProxy != null)
            mProxy.onVideoSizeChanged(mp, width, height);
    }

    // This is true only when the player is buffering and paused
    private boolean mPlayerBuffering = false;

    public boolean getPlayerBuffering() {
        return mPlayerBuffering;
    }

    public void setPlayerBuffering(boolean playerBuffering) {
        mPlayerBuffering = playerBuffering;
        if (mProgressView != null)
            switchProgressView(playerBuffering);
    }

    private void switchProgressView(boolean playerBuffering) {
        if (playerBuffering)
            mProgressView.setVisibility(View.VISIBLE);
        else
            mProgressView.setVisibility(View.GONE);
    }

    class MyVideoTextureView extends VideoTextureView {
        public MyVideoTextureView(Context context, SurfaceTexture surface, int textureName,
                boolean surfaceTextureReady) {
            super(context, surface, textureName, surfaceTextureReady);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            mFullscreenWidth = getDefaultSize(mProxy.getVideoWidth(), widthMeasureSpec);
            mFullscreenHeight = getDefaultSize(mProxy.getVideoHeight(), heightMeasureSpec);
            if (mProxy.getVideoWidth() > 0 && mProxy.getVideoHeight() > 0) {
                if ( mProxy.getVideoWidth() * mFullscreenHeight > mFullscreenWidth * mProxy.getVideoHeight() ) {
                    mFullscreenHeight = mFullscreenWidth * mProxy.getVideoHeight() / mProxy.getVideoWidth();
                } else if ( mProxy.getVideoWidth() * mFullscreenHeight < mFullscreenWidth * mProxy.getVideoHeight() ) {
                    mFullscreenWidth = mFullscreenHeight * mProxy.getVideoWidth() / mProxy.getVideoHeight();
                }
            }
            setMeasuredDimension(mFullscreenWidth, mFullscreenHeight);

            if (mAnimationState == ANIMATION_STATE_NONE) {
                // Make sure the view is visible
                mTextureView.setVisibility(View.VISIBLE);
                // Configuring VideoTextureView to inline bounds
                mTextureView.setTranslationX(getInlineXOffset());
                mTextureView.setTranslationY(getInlineYOffset());
                mTextureView.setScaleX(getInlineXScale());
                mTextureView.setScaleY(getInlineYScale());

                // inline to fullscreen zoom out animation
                mTextureView.animate().setListener(new AnimatorListenerAdapter() {
                    public void onAnimationEnd(Animator animation) {
                        if (mIsFullscreen)
                            attachMediaController();
                        mAnimationState = ANIMATION_STATE_FINISHED;
                    }
                });
                mTextureView.animate().setDuration(ANIMATION_DURATION);
                mAnimationState = ANIMATION_STATE_STARTED;
                mTextureView.animate().scaleX(1.0f).scaleY(1.0f).translationX(0.0f).translationY(0.0f);
            }
        }
    }

    // Note: Call this for fullscreen mode only
    // If MediaPlayer is prepared, enable the buttons
    private void attachMediaController() {
        if (mMediaController == null) {
            MediaController mc = new FullScreenMediaController(mProxy.getContext(), mLayout);
            mc.setSystemUiVisibility(mLayout.getSystemUiVisibility());
            mMediaController = mc;
            mMediaController.setMediaPlayer(this);
            mMediaController.setAnchorView(mTextureView);
            mMediaController.setEnabled(false);
        }

        // Get the capabilities of the player for this stream
        // This should only be called when MediaPlayer is in prepared state
        // Otherwise data will return invalid values
        if (mCurrentState == STATE_PREPARED) {
            Metadata data = mPlayer.getMetadata(MediaPlayer.METADATA_ALL,
                    MediaPlayer.BYPASS_METADATA_FILTER);
            if (data != null) {
                mCanPause = !data.has(Metadata.PAUSE_AVAILABLE)
                    || data.getBoolean(Metadata.PAUSE_AVAILABLE);
                mCanSeekBack = !data.has(Metadata.SEEK_BACKWARD_AVAILABLE)
                    || data.getBoolean(Metadata.SEEK_BACKWARD_AVAILABLE);
                mCanSeekForward = !data.has(Metadata.SEEK_FORWARD_AVAILABLE)
                    || data.getBoolean(Metadata.SEEK_FORWARD_AVAILABLE);
            } else {
                mCanPause = mCanSeekBack = mCanSeekForward = true;
            }
            // mMediaController status depends on the Metadata result, so put it
            // after reading the MetaData
            mMediaController.setEnabled(true);

            // If paused, should show the controller for ever!
            if (isPlaying())
                mMediaController.show();
            else
                mMediaController.show(0);
        }
    }

    private void toggleMediaControlsVisiblity() {
        if (mMediaController.isShowing())
            mMediaController.hide();
        else
            mMediaController.show();
    }

    private final WebChromeClient.CustomViewCallback mCallback =
        new WebChromeClient.CustomViewCallback() {
            public void onCustomViewHidden() {
                mProxy.prepareExitFullscreen();
            }
        };

    public void onVideoTextureUpdated(SurfaceTexture surface) {
       mSurfaceTextureReady = true;
    }

    public void enterFullScreenVideoState(WebView webView, float x, float y, float w, float h) {
        if (mIsFullscreen == true)
            return;
        mIsFullscreen = true;
        mAnimationState = ANIMATION_STATE_NONE;
        mCurrentBufferPercentage = 0;
        mPlayer.setOnBufferingUpdateListener(mBufferingUpdateListener);
        mInlineX = x;
        mInlineY = y;
        mInlineWidth = w;
        mInlineHeight = h;

        assert(mSurfaceTexture != null);
        mTextureView = new MyVideoTextureView(mProxy.getContext(), getSurfaceTexture(),
                mProxy.getTextureName(), mSurfaceTextureReady);
        mTextureView.setOnTouchListener(this);

        mLayout = new FrameLayout(mProxy.getContext());
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER);

        // Make fullscreen video visible here only if the size is known
        // Otherwise incorrect aspect ratio may be shown
        if (mProxy.getVideoWidth() > 0 && mProxy.getVideoHeight() > 0)
            mTextureView.setVisibility(View.VISIBLE);
        else
            mTextureView.setVisibility(View.INVISIBLE);

        mTextureView.setVideoTextureListener(this);

        mLayout.addView(mTextureView, layoutParams);

        mLayout.setVisibility(View.VISIBLE);
        WebChromeClient client = webView.getWebChromeClient();
        if (client != null) {
            client.onShowCustomView(mLayout, mCallback);
            // Plugins like Flash will draw over the video so hide
            // them while we're playing.
            if (webView.getViewManager() != null)
                webView.getViewManager().hideAll();

            // Add progress view
            mProgressView = client.getVideoLoadingProgressView();
            if (mProgressView != null) {
                mLayout.addView(mProgressView, layoutParams);
                if (mCurrentState != STATE_PREPARED)
                    mProgressView.setVisibility(View.VISIBLE);
                else
                    mProgressView.setVisibility(View.GONE);
            }
        }
    }

    public void exitFullScreenVideoState(float x, float y, float w, float h) {
        if (mIsFullscreen == false) {
            return;
        }
        mIsFullscreen = false;

        mInlineX = x;
        mInlineY = y;
        mInlineWidth = w;
        mInlineHeight = h;

        // Don't show the controller after exiting the full screen.
        if (mMediaController != null) {
            mMediaController.hide();
            mMediaController = null;
        }

        if (mAnimationState == ANIMATION_STATE_STARTED) {
            mTextureView.animate().cancel();
            finishExitingFullscreen();
        } else {
            // fullscreen to inline zoom in animation
            mTextureView.animate().setListener(new AnimatorListenerAdapter() {
                public void onAnimationEnd(Animator animation) {
                    finishExitingFullscreen();
                }
            });

            mTextureView.animate().setDuration(ANIMATION_DURATION);
            mTextureView.animate().scaleX(getInlineXScale()).scaleY(getInlineYScale()).translationX(getInlineXOffset()).translationY(getInlineYOffset());
        }
    }

    // MediaController FUNCTIONS:
    public boolean canPause() {
        return mCanPause;
    }

    public boolean canSeekBackward() {
        return mCanSeekBack;
    }

    public boolean canSeekForward() {
        return mCanSeekForward;
    }

    public int getBufferPercentage() {
        if (mPlayer != null) {
            return mCurrentBufferPercentage;
        }
        return 0;
    }

    // Other listeners functions:
    private MediaPlayer.OnBufferingUpdateListener mBufferingUpdateListener =
        new MediaPlayer.OnBufferingUpdateListener() {
        public void onBufferingUpdate(MediaPlayer mp, int percent) {
            mCurrentBufferPercentage = percent;
        }
    };

    public boolean onTouch(View v, MotionEvent event) {
        if (mIsFullscreen && mMediaController != null)
            toggleMediaControlsVisiblity();
        return false;
    }

    static class FullScreenMediaController extends MediaController {

        View mVideoView;

        public FullScreenMediaController(Context context, View video) {
            super(context);
            mVideoView = video;
        }

        @Override
        public void show() {
            super.show();
            if (mVideoView != null) {
                mVideoView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        }

        @Override
        public void hide() {
            if (mVideoView != null) {
                mVideoView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            }
            super.hide();
        }
    }

    private float getInlineXOffset() {
        updateDisplaySize();
        if (mInlineWidth < 0 || mInlineHeight < 0)
            return 0;
        else
            return mInlineX - (mDisplaySize.x - mInlineWidth) / 2;
    }

    private float getInlineYOffset() {
        updateDisplaySize();
        if (mInlineWidth < 0 || mInlineHeight < 0)
            return 0;
        else
            return (mDisplaySize.y - mInlineHeight) / 2 - mInlineY;
    }

    private float getInlineXScale() {
        if (mInlineWidth < 0 || mInlineHeight < 0 || mFullscreenWidth == 0)
            return 0;
        else
            return mInlineWidth / mFullscreenWidth;
    }

    private float getInlineYScale() {
        if (mInlineWidth < 0 || mInlineHeight < 0 || mFullscreenHeight == 0)
            return 0;
        else
            return mInlineHeight / mFullscreenHeight;
    }

    private void updateDisplaySize() {
        WindowManager wm = (WindowManager)mProxy.getContext().getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        display.getSize(mDisplaySize);
    }

    private void finishExitingFullscreen() {
        mProxy.dispatchOnStopFullScreen();
        mLayout.removeView(mTextureView);
        mTextureView = null;

        if (mProgressView != null) {
            mLayout.removeView(mProgressView);
            mProgressView = null;
        }
        mLayout = null;
        // Re enable plugin views.
        mProxy.getWebView().getViewManager().showAll();

        // Set the frame available listener back to the inline listener
        setInlineFrameAvailableListener();
    }

}




