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
package com.android.quickstep.inputconsumers;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_POINTER_UP;
import static android.view.MotionEvent.ACTION_UP;
import static android.view.MotionEvent.INVALID_POINTER_ID;
import static com.android.launcher3.PagedView.ACTION_MOVE_ALLOW_EASY_FLING;
import static com.android.launcher3.PagedView.DEBUG_FAILED_QUICKSWITCH;
import static com.android.launcher3.Utilities.EDGE_NAV_BAR;
import static com.android.launcher3.Utilities.squaredHypot;
import static com.android.launcher3.util.TraceHelper.FLAG_CHECK_FOR_RACE_CONDITIONS;
import static com.android.launcher3.util.VelocityUtils.PX_PER_MS;
import static com.android.quickstep.GestureState.STATE_OVERSCROLL_WINDOW_CREATED;
import static com.android.quickstep.util.ActiveGestureLog.INTENT_EXTRA_LOG_TRACE_ID;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

import androidx.annotation.UiThread;

import com.android.launcher3.R;
import com.android.launcher3.testing.TestLogging;
import com.android.launcher3.testing.TestProtocol;
import com.android.launcher3.tracing.InputConsumerProto;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.TraceHelper;
import com.android.quickstep.AbsSwipeUpHandler;
import com.android.quickstep.AbsSwipeUpHandler.Factory;
import com.android.quickstep.BaseActivityInterface;
import com.android.quickstep.GestureState;
import com.android.quickstep.InputConsumer;
import com.android.quickstep.RecentsAnimationCallbacks;
import com.android.quickstep.RecentsAnimationDeviceState;
import com.android.quickstep.RotationTouchHelper;
import com.android.quickstep.TaskAnimationManager;
import com.android.quickstep.TouchInteractionService;
import com.android.quickstep.util.ActiveGestureLog;
import com.android.quickstep.util.CachedEventDispatcher;
import com.android.quickstep.util.MotionPauseDetector;
import com.android.quickstep.util.NavBarPosition;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputChannelCompat.InputEventReceiver;
import com.android.systemui.shared.system.InputMonitorCompat;

import java.util.function.Consumer;

/**
 * Input consumer for handling events originating from an activity other than Launcher
 */
@TargetApi(Build.VERSION_CODES.P)
public class OtherActivityInputConsumer extends ContextWrapper implements InputConsumer {

    public static final String DOWN_EVT = "OtherActivityInputConsumer.DOWN";
    private static final String UP_EVT = "OtherActivityInputConsumer.UP";

    // TODO: Move to quickstep contract
    public static final float QUICKSTEP_TOUCH_SLOP_RATIO_TWO_BUTTON = 9;
    public static final float QUICKSTEP_TOUCH_SLOP_RATIO_GESTURAL = 2;

    private final RecentsAnimationDeviceState mDeviceState;
    private final NavBarPosition mNavBarPosition;
    private final TaskAnimationManager mTaskAnimationManager;
    private final GestureState mGestureState;
    private final RotationTouchHelper mRotationTouchHelper;
    private RecentsAnimationCallbacks mActiveCallbacks;
    private final CachedEventDispatcher mRecentsViewDispatcher = new CachedEventDispatcher();
    private final InputMonitorCompat mInputMonitorCompat;
    private final InputEventReceiver mInputEventReceiver;
    private final BaseActivityInterface mActivityInterface;

    private final AbsSwipeUpHandler.Factory mHandlerFactory;

    private final Consumer<OtherActivityInputConsumer> mOnCompleteCallback;
    private final MotionPauseDetector mMotionPauseDetector;
    private final float mMotionPauseMinDisplacement;

    private VelocityTracker mVelocityTracker;

    private AbsSwipeUpHandler mInteractionHandler;

    private final boolean mIsDeferredDownTarget;
    private final PointF mDownPos = new PointF();
    private final PointF mLastPos = new PointF();
    private int mActivePointerId = INVALID_POINTER_ID;

    private int mLastRotation = -1;

    // Distance after which we start dragging the window.
    private final float mTouchSlop;

    private final float mSquaredTouchSlop;
    private final boolean mDisableHorizontalSwipe;

    // Slop used to check when we start moving window.
    private boolean mPassedWindowMoveSlop;
    // Slop used to determine when we say that the gesture has started.
    private boolean mPassedPilferInputSlop;
    // Same as mPassedPilferInputSlop, except when continuing a gesture mPassedPilferInputSlop is
    // initially true while this one is false.
    private boolean mPassedSlopOnThisGesture;

    // Might be displacement in X or Y, depending on the direction we are swiping from the nav bar.
    private float mStartDisplacement;

    private final DisplayManager mDisplayManager;

    private Handler mMainThreadHandler;
    private Runnable mCancelRecentsAnimationRunnable = () -> {
        ActivityManagerWrapper.getInstance().cancelRecentsAnimation(
                true /* restoreHomeStackPosition */);
    };

    public OtherActivityInputConsumer(Context base, RecentsAnimationDeviceState deviceState,
                                      TaskAnimationManager taskAnimationManager, GestureState gestureState,
                                      boolean isDeferredDownTarget, Consumer<OtherActivityInputConsumer> onCompleteCallback,
                                      InputMonitorCompat inputMonitorCompat, InputEventReceiver inputEventReceiver,
                                      boolean disableHorizontalSwipe, Factory handlerFactory) {
        super(base);
        mDeviceState = deviceState;
        mNavBarPosition = mDeviceState.getNavBarPosition();
        mTaskAnimationManager = taskAnimationManager;
        mGestureState = gestureState;
        mMainThreadHandler = new Handler(Looper.getMainLooper());
        mHandlerFactory = handlerFactory;
        mActivityInterface = mGestureState.getActivityInterface();

        mMotionPauseDetector = new MotionPauseDetector(base, false,
                mNavBarPosition.isLeftEdge() || mNavBarPosition.isRightEdge()
                        ? MotionEvent.AXIS_X : MotionEvent.AXIS_Y);
        mMotionPauseMinDisplacement = base.getResources().getDimension(
                R.dimen.motion_pause_detector_min_displacement_from_app);
        mOnCompleteCallback = onCompleteCallback;
        mVelocityTracker = VelocityTracker.obtain();
        mInputMonitorCompat = inputMonitorCompat;
        mInputEventReceiver = inputEventReceiver;

        boolean continuingPreviousGesture = mTaskAnimationManager.isRecentsAnimationRunning();
        mIsDeferredDownTarget = !continuingPreviousGesture && isDeferredDownTarget;

        float slopMultiplier = mDeviceState.isFullyGesturalNavMode()
                ? QUICKSTEP_TOUCH_SLOP_RATIO_GESTURAL
                : QUICKSTEP_TOUCH_SLOP_RATIO_TWO_BUTTON;
        mTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        mSquaredTouchSlop = slopMultiplier * mTouchSlop * mTouchSlop;

        mPassedPilferInputSlop = mPassedWindowMoveSlop = continuingPreviousGesture;
        mDisableHorizontalSwipe = !mPassedPilferInputSlop && disableHorizontalSwipe;
        mRotationTouchHelper = mDeviceState.getRotationTouchHelper();
        mDisplayManager = getSystemService(DisplayManager.class);
    }

    @Override
    public int getType() {
        return TYPE_OTHER_ACTIVITY;
    }

    @Override
    public boolean isConsumerDetachedFromGesture() {
        return true;
    }

    private void forceCancelGesture(MotionEvent ev) {
        int action = ev.getAction();
        ev.setAction(ACTION_CANCEL);
        finishTouchTracking(ev);
        ev.setAction(action);
    }

    @Override
    public void onMotionEvent(MotionEvent ev) {
        if (mVelocityTracker == null) {
            return;
        }

        if (TouchInteractionService.ENABLE_PER_WINDOW_INPUT_ROTATION) {
            final Display display = mDisplayManager.getDisplay(mDeviceState.getDisplayId());
            final int rotation = display.getRotation();
            if (rotation != mLastRotation) {
                // If rotation changes, reset tracking to avoid degenerate velocities.
                mLastPos.set(ev.getX(), ev.getY());
                mVelocityTracker.clear();
                mLastRotation = rotation;
            }
        }

        // Proxy events to recents view
        if (mPassedWindowMoveSlop && mInteractionHandler != null
                && !mRecentsViewDispatcher.hasConsumer()) {
            mRecentsViewDispatcher.setConsumer(mInteractionHandler
                    .getRecentsViewDispatcher(mNavBarPosition.getRotation()));
            int action = ev.getAction();
            ev.setAction(ACTION_MOVE_ALLOW_EASY_FLING);
            mRecentsViewDispatcher.dispatchEvent(ev);
            ev.setAction(action);
        }
        int edgeFlags = ev.getEdgeFlags();
        ev.setEdgeFlags(edgeFlags | EDGE_NAV_BAR);
        mRecentsViewDispatcher.dispatchEvent(ev);
        ev.setEdgeFlags(edgeFlags);

        mVelocityTracker.addMovement(ev);
        if (ev.getActionMasked() == ACTION_POINTER_UP) {
            mVelocityTracker.clear();
            mMotionPauseDetector.clear();
        }

        switch (ev.getActionMasked()) {
            case ACTION_DOWN: {
                // Until we detect the gesture, handle events as we receive them
                mInputEventReceiver.setBatchingEnabled(false);

                Object traceToken = TraceHelper.INSTANCE.beginSection(DOWN_EVT,
                        FLAG_CHECK_FOR_RACE_CONDITIONS);
                mActivePointerId = ev.getPointerId(0);
                mDownPos.set(ev.getX(), ev.getY());
                mLastPos.set(mDownPos);

                // Start the window animation on down to give more time for launcher to draw if the
                // user didn't start the gesture over the back button
                if (!mIsDeferredDownTarget) {
                    startTouchTrackingForWindowAnimation(ev.getEventTime());
                }

                TraceHelper.INSTANCE.endSection(traceToken);
                break;
            }
            case ACTION_POINTER_DOWN: {
                if (!mPassedPilferInputSlop) {
                    // Cancel interaction in case of multi-touch interaction
                    int ptrIdx = ev.getActionIndex();
                    if (!mRotationTouchHelper.isInSwipeUpTouchRegion(ev, ptrIdx)) {
                        forceCancelGesture(ev);
                    }
                }
                break;
            }
            case ACTION_POINTER_UP: {
                int ptrIdx = ev.getActionIndex();
                int ptrId = ev.getPointerId(ptrIdx);
                if (ptrId == mActivePointerId) {
                    final int newPointerIdx = ptrIdx == 0 ? 1 : 0;
                    mDownPos.set(
                            ev.getX(newPointerIdx) - (mLastPos.x - mDownPos.x),
                            ev.getY(newPointerIdx) - (mLastPos.y - mDownPos.y));
                    mLastPos.set(ev.getX(newPointerIdx), ev.getY(newPointerIdx));
                    mActivePointerId = ev.getPointerId(newPointerIdx);
                }
                break;
            }
            case ACTION_MOVE: {
                int pointerIndex = ev.findPointerIndex(mActivePointerId);
                if (pointerIndex == INVALID_POINTER_ID) {
                    break;
                }
                mLastPos.set(ev.getX(pointerIndex), ev.getY(pointerIndex));
                float displacement = getDisplacement(ev);
                float displacementX = mLastPos.x - mDownPos.x;
                float displacementY = mLastPos.y - mDownPos.y;

                if (!mPassedWindowMoveSlop) {
                    if (!mIsDeferredDownTarget) {
                        // Normal gesture, ensure we pass the drag slop before we start tracking
                        // the gesture
                        if (Math.abs(displacement) > mTouchSlop) {
                            mPassedWindowMoveSlop = true;
                            mStartDisplacement = Math.min(displacement, -mTouchSlop);
                        }
                    }
                }

                float horizontalDist = Math.abs(displacementX);
                float upDist = -displacement;
                boolean passedSlop = squaredHypot(displacementX, displacementY)
                        >= mSquaredTouchSlop;
                if (!mPassedSlopOnThisGesture && passedSlop) {
                    mPassedSlopOnThisGesture = true;
                }
                // Until passing slop, we don't know what direction we're going, so assume
                // we're quick switching to avoid translating recents away when continuing
                // the gesture (in which case mPassedPilferInputSlop starts as true).
                boolean haveNotPassedSlopOnContinuedGesture =
                        !mPassedSlopOnThisGesture && mPassedPilferInputSlop;
                boolean isLikelyToStartNewTask = haveNotPassedSlopOnContinuedGesture
                        || horizontalDist > upDist;

                if (!mPassedPilferInputSlop) {
                    if (passedSlop) {
                        if (mDisableHorizontalSwipe
                                && Math.abs(displacementX) > Math.abs(displacementY)) {
                            // Horizontal gesture is not allowed in this region
                            forceCancelGesture(ev);
                            break;
                        }

                        mPassedPilferInputSlop = true;

                        if (mIsDeferredDownTarget) {
                            // Deferred gesture, start the animation and gesture tracking once
                            // we pass the actual touch slop
                            startTouchTrackingForWindowAnimation(ev.getEventTime());
                        }
                        if (!mPassedWindowMoveSlop) {
                            mPassedWindowMoveSlop = true;
                            mStartDisplacement = Math.min(displacement, -mTouchSlop);

                        }
                        notifyGestureStarted(isLikelyToStartNewTask);
                    }
                }

                if (mInteractionHandler != null) {
                    if (mPassedWindowMoveSlop) {
                        // Move
                        mInteractionHandler.updateDisplacement(displacement - mStartDisplacement);
                    }

                    if (mDeviceState.isFullyGesturalNavMode()) {
                        mMotionPauseDetector.setDisallowPause(upDist < mMotionPauseMinDisplacement
                                || isLikelyToStartNewTask);
                        mMotionPauseDetector.addPosition(ev);
                        mInteractionHandler.setIsLikelyToStartNewTask(isLikelyToStartNewTask);
                    }
                }
                break;
            }
            case ACTION_CANCEL:
            case ACTION_UP: {
                if (DEBUG_FAILED_QUICKSWITCH && !mPassedWindowMoveSlop) {
                    float displacementX = mLastPos.x - mDownPos.x;
                    float displacementY = mLastPos.y - mDownPos.y;
                    Log.d("Quickswitch", "mPassedWindowMoveSlop=false"
                            + " disp=" + squaredHypot(displacementX, displacementY)
                            + " slop=" + mSquaredTouchSlop);
                }
                finishTouchTracking(ev);
                break;
            }
        }
    }

    private void notifyGestureStarted(boolean isLikelyToStartNewTask) {
        ActiveGestureLog.INSTANCE.addLog("startQuickstep");
        if (mInteractionHandler == null) {
            return;
        }
        TestLogging.recordEvent(TestProtocol.SEQUENCE_PILFER, "pilferPointers");
        mInputMonitorCompat.pilferPointers();
        // Once we detect the gesture, we can enable batching to reduce further updates
        mInputEventReceiver.setBatchingEnabled(true);

        // Notify the handler that the gesture has actually started
        mInteractionHandler.onGestureStarted(isLikelyToStartNewTask);
    }

    private void startTouchTrackingForWindowAnimation(long touchTimeMs) {
        ActiveGestureLog.INSTANCE.addLog("startRecentsAnimation");

        mInteractionHandler = mHandlerFactory.newHandler(mGestureState, touchTimeMs);
        mInteractionHandler.setGestureEndCallback(this::onInteractionGestureFinished);
        mMotionPauseDetector.setOnMotionPauseListener(mInteractionHandler.getMotionPauseListener());
        mInteractionHandler.initWhenReady();

        if (mTaskAnimationManager.isRecentsAnimationRunning()) {
            mActiveCallbacks = mTaskAnimationManager.continueRecentsAnimation(mGestureState);
            mActiveCallbacks.addListener(mInteractionHandler);
            mTaskAnimationManager.notifyRecentsAnimationState(mInteractionHandler);
            notifyGestureStarted(true /*isLikelyToStartNewTask*/);
        } else {
            Intent intent = new Intent(mInteractionHandler.getLaunchIntent());
            intent.putExtra(INTENT_EXTRA_LOG_TRACE_ID, mGestureState.getGestureId());
            mActiveCallbacks = mTaskAnimationManager.startRecentsAnimation(mGestureState, intent,
                    mInteractionHandler);
        }
    }

    /**
     * Called when the gesture has ended. Does not correlate to the completion of the interaction as
     * the animation can still be running.
     */
    private void finishTouchTracking(MotionEvent ev) {
        Object traceToken = TraceHelper.INSTANCE.beginSection(UP_EVT,
                FLAG_CHECK_FOR_RACE_CONDITIONS);

        if (mPassedWindowMoveSlop && mInteractionHandler != null) {
            if (ev.getActionMasked() == ACTION_CANCEL) {
                mInteractionHandler.onGestureCancelled();
            } else {
                mVelocityTracker.computeCurrentVelocity(PX_PER_MS);
                float velocityX = mVelocityTracker.getXVelocity(mActivePointerId);
                float velocityY = mVelocityTracker.getYVelocity(mActivePointerId);
                float velocity = mNavBarPosition.isRightEdge()
                        ? velocityX
                        : mNavBarPosition.isLeftEdge()
                        ? -velocityX
                        : velocityY;
                mInteractionHandler.updateDisplacement(getDisplacement(ev) - mStartDisplacement);
                mInteractionHandler.onGestureEnded(velocity, new PointF(velocityX, velocityY),
                        mDownPos);
            }
        } else {
            // Since we start touch tracking on DOWN, we may reach this state without actually
            // starting the gesture. In that case, just cleanup immediately.
            onConsumerAboutToBeSwitched();
            onInteractionGestureFinished();

            // Cancel the recents animation if SysUI happens to handle UP before we have a chance
            // to start the recents animation. In addition, workaround for b/126336729 by delaying
            // the cancel of the animation for a period, in case SysUI is slow to handle UP and we
            // handle DOWN & UP and move the home stack before SysUI can start the activity
            mMainThreadHandler.removeCallbacks(mCancelRecentsAnimationRunnable);
            mMainThreadHandler.postDelayed(mCancelRecentsAnimationRunnable, 100);
        }
        mVelocityTracker.recycle();
        mVelocityTracker = null;
        mMotionPauseDetector.clear();
        TraceHelper.INSTANCE.endSection(traceToken);
    }

    @Override
    public void notifyOrientationSetup() {
        mRotationTouchHelper.onStartGesture();
    }

    @Override
    public void onConsumerAboutToBeSwitched() {
        Preconditions.assertUIThread();
        mMainThreadHandler.removeCallbacks(mCancelRecentsAnimationRunnable);
        if (mInteractionHandler != null) {
            // The consumer is being switched while we are active. Set up the shared state to be
            // used by the next animation
            removeListener();
            mInteractionHandler.onConsumerAboutToBeSwitched();
        }
    }

    @UiThread
    private void onInteractionGestureFinished() {
        Preconditions.assertUIThread();
        removeListener();
        mInteractionHandler = null;
        mOnCompleteCallback.accept(this);
    }

    private void removeListener() {
        if (mActiveCallbacks != null) {
            mActiveCallbacks.removeListener(mInteractionHandler);
        }
    }

    private float getDisplacement(MotionEvent ev) {
        if (mNavBarPosition.isRightEdge()) {
            return ev.getX() - mDownPos.x;
        } else if (mNavBarPosition.isLeftEdge()) {
            return mDownPos.x - ev.getX();
        } else {
            return ev.getY() - mDownPos.y;
        }
    }

    @Override
    public boolean allowInterceptByParent() {
        return !mPassedPilferInputSlop || mGestureState.hasState(STATE_OVERSCROLL_WINDOW_CREATED);
    }

    @Override
    public void writeToProtoInternal(InputConsumerProto.Builder inputConsumerProto) {
        if (mInteractionHandler != null) {
            mInteractionHandler.writeToProto(inputConsumerProto);
        }
    }
}
