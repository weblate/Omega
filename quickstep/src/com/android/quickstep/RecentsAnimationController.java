/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.quickstep;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.view.IRecentsAnimationController;
import android.view.SurfaceControl;
import android.window.PictureInPictureSurfaceTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.RunnableList;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.InteractionJankMonitorWrapper;
import com.android.systemui.shared.system.RecentsAnimationControllerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;

import java.util.function.Consumer;

/**
 * Wrapper around RecentsAnimationControllerCompat to help with some synchronization
 */
public class RecentsAnimationController {

    private final RecentsAnimationControllerCompat mController;
    private final Consumer<RecentsAnimationController> mOnFinishedListener;
    private final boolean mAllowMinimizeSplitScreen;

    private boolean mUseLauncherSysBarFlags = false;
    private boolean mSplitScreenMinimized = false;
    private boolean mFinishRequested = false;
    private RunnableList mPendingFinishCallbacks = new RunnableList();

    public RecentsAnimationController(RecentsAnimationControllerCompat controller,
                                      boolean allowMinimizeSplitScreen,
                                      Consumer<RecentsAnimationController> onFinishedListener) {
        mController = controller;
        mOnFinishedListener = onFinishedListener;
        mAllowMinimizeSplitScreen = allowMinimizeSplitScreen;
    }

    /**
     * Synchronously takes a screenshot of the task with the given {@param taskId} if the task is
     * currently being animated.
     */
    public ThumbnailData screenshotTask(int taskId) {
        return mController.screenshotTask(taskId);
    }

    /**
     * Indicates that the gesture has crossed the window boundary threshold and system UI can be
     * update the system bar flags accordingly.
     */
    public void setUseLauncherSystemBarFlags(boolean useLauncherSysBarFlags) {
        if (mUseLauncherSysBarFlags != useLauncherSysBarFlags) {
            mUseLauncherSysBarFlags = useLauncherSysBarFlags;
            UI_HELPER_EXECUTOR.execute(() -> {
                mController.setAnimationTargetsBehindSystemBars(!useLauncherSysBarFlags);
            });
        }
    }

    /**
     * Indicates that the gesture has crossed the window boundary threshold and we should minimize
     * if we are in splitscreen.
     */
    public void setSplitScreenMinimized(boolean splitScreenMinimized) {
        if (!mAllowMinimizeSplitScreen) {
            return;
        }
        if (mSplitScreenMinimized != splitScreenMinimized) {
            mSplitScreenMinimized = splitScreenMinimized;
            UI_HELPER_EXECUTOR.execute(() -> {
                SystemUiProxy p = SystemUiProxy.INSTANCE.getNoCreate();
                if (p != null) {
                    p.setSplitScreenMinimized(splitScreenMinimized);
                }
            });
        }
    }

    /**
     * Remove task remote animation target from
     * {@link RecentsAnimationCallbacks#onTaskAppeared(RemoteAnimationTargetCompat)}}.
     */
    @UiThread
    public void removeTaskTarget(@NonNull RemoteAnimationTargetCompat target) {
        UI_HELPER_EXECUTOR.execute(() -> mController.removeTask(target.taskId));
    }

    @UiThread
    public void finishAnimationToHome() {
        finishController(true /* toRecents */, null, false /* sendUserLeaveHint */);
    }

    @UiThread
    public void finishAnimationToApp() {
        finishController(false /* toRecents */, null, false /* sendUserLeaveHint */);
    }

    /**
     * See {@link #finish(boolean, Runnable, boolean)}
     */
    @UiThread
    public void finish(boolean toRecents, Runnable onFinishComplete) {
        finish(toRecents, onFinishComplete, false /* sendUserLeaveHint */);
    }

    /**
     * @param onFinishComplete  A callback that runs on the main thread after the animation
     *                          controller has finished on the background thread.
     * @param sendUserLeaveHint Determines whether userLeaveHint flag will be set on the pausing
     *                          activity. If userLeaveHint is true, the activity will enter into
     *                          picture-in-picture mode upon being paused.
     */
    @UiThread
    public void finish(boolean toRecents, Runnable onFinishComplete, boolean sendUserLeaveHint) {
        Preconditions.assertUIThread();
        finishController(toRecents, onFinishComplete, sendUserLeaveHint);
    }

    @UiThread
    public void finishController(boolean toRecents, Runnable callback, boolean sendUserLeaveHint) {
        if (mFinishRequested) {
            // If finishing, add to pending finish callbacks, otherwise, if finished, adding to the
            // destroyed RunnableList will just trigger the callback to be called immediately
            mPendingFinishCallbacks.add(callback);
            return;
        }

        // Finish not yet requested
        mFinishRequested = true;
        mOnFinishedListener.accept(this);
        mPendingFinishCallbacks.add(callback);
        UI_HELPER_EXECUTOR.execute(() -> {
            mController.finish(toRecents, sendUserLeaveHint);
            InteractionJankMonitorWrapper.end(InteractionJankMonitorWrapper.CUJ_QUICK_SWITCH);
            InteractionJankMonitorWrapper.end(InteractionJankMonitorWrapper.CUJ_APP_CLOSE_TO_HOME);
            MAIN_EXECUTOR.execute(mPendingFinishCallbacks::executeAllAndDestroy);
        });
    }

    /**
     * @see IRecentsAnimationController#cleanupScreenshot()
     */
    @UiThread
    public void cleanupScreenshot() {
        UI_HELPER_EXECUTOR.execute(() -> mController.cleanupScreenshot());
    }

    /**
     * @see RecentsAnimationControllerCompat#detachNavigationBarFromApp
     */
    @UiThread
    public void detachNavigationBarFromApp(boolean moveHomeToTop) {
        UI_HELPER_EXECUTOR.execute(() -> mController.detachNavigationBarFromApp(moveHomeToTop));
    }

    /**
     * @see IRecentsAnimationController#animateNavigationBarToApp(long)
     */
    @UiThread
    public void animateNavigationBarToApp(long duration) {
        UI_HELPER_EXECUTOR.execute(() -> mController.animateNavigationBarToApp(duration));
    }

    /**
     * @see IRecentsAnimationController#setWillFinishToHome(boolean)
     */
    @UiThread
    public void setWillFinishToHome(boolean willFinishToHome) {
        UI_HELPER_EXECUTOR.execute(() -> mController.setWillFinishToHome(willFinishToHome));
    }

    /**
     * Sets the final surface transaction on a Task. This is used by Launcher to notify the system
     * that animating Activity to PiP has completed and the associated task surface should be
     * updated accordingly. This should be called before `finish`
     *
     * @param taskId            for which the leash should be updated
     * @param finishTransaction the transaction to transfer to the task surface control after the
     *                          leash is removed
     * @param overlay           the surface control for an overlay being shown above the pip (can be null)
     */
    public void setFinishTaskTransaction(int taskId,
                                         PictureInPictureSurfaceTransaction finishTransaction,
                                         SurfaceControl overlay) {
        UI_HELPER_EXECUTOR.execute(
                () -> mController.setFinishTaskTransaction(taskId, finishTransaction, overlay));
    }

    /**
     * Enables the input consumer to start intercepting touches in the app window.
     */
    public void enableInputConsumer() {
        UI_HELPER_EXECUTOR.submit(() -> {
            mController.hideCurrentInputMethod();
            mController.setInputConsumerEnabled(true);
        });
    }

    /** @return wrapper controller. */
    public RecentsAnimationControllerCompat getController() {
        return mController;
    }
}
