/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.animation.LayoutTransition;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.PagedView;
import com.android.launcher3.R;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.uioverrides.RecentsViewStateController;
import com.android.systemui.shared.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.shared.recents.model.RecentsTaskLoader;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.TaskStack;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.WindowManagerWrapper;

import java.util.ArrayList;

/**
 * A list of recent tasks.
 */
public class RecentsView extends PagedView {

    /** Designates how "curvy" the carousel is from 0 to 1, where 0 is a straight line. */
    private static final float CURVE_FACTOR = 0.25f;
    /** A circular curve of x from 0 to 1, where 0 is the center of the screen and 1 is the edge. */
    private static final TimeInterpolator CURVE_INTERPOLATOR
        = x -> (float) (1 - Math.sqrt(1 - Math.pow(x, 2)));
    /**
     * The alpha of a black scrim on a page in the carousel as it leaves the screen.
     * In the resting position of the carousel, the adjacent pages have about half this scrim.
     */
    private static final float MAX_PAGE_SCRIM_ALPHA = 0.8f;

    private boolean mOverviewStateEnabled;
    private boolean mTaskStackListenerRegistered;
    private LayoutTransition mLayoutTransition;

    private TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskSnapshotChanged(int taskId, ThumbnailData snapshot) {
            for (int i = 0; i < getChildCount(); i++) {
                final TaskView taskView = (TaskView) getChildAt(i);
                if (taskView.getTask().key.id == taskId) {
                    taskView.getThumbnail().setThumbnail(snapshot);
                    return;
                }
            }
        }
    };

    private RecentsViewStateController mStateController;

    public RecentsView(Context context) {
        this(context, null);
    }

    public RecentsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setPageSpacing((int) getResources().getDimension(R.dimen.recents_page_spacing));
        enableFreeScroll(true);
        setupLayoutTransition();
    }

    private void setupLayoutTransition() {
        // We want to show layout transitions when pages are deleted, to close the gap.
        mLayoutTransition = new LayoutTransition();
        mLayoutTransition.enableTransitionType(LayoutTransition.DISAPPEARING);
        mLayoutTransition.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);

        mLayoutTransition.disableTransitionType(LayoutTransition.APPEARING);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        setLayoutTransition(mLayoutTransition);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        Rect padding = getPadding(Launcher.getLauncher(getContext()));
        setPadding(padding.left, padding.top, padding.right, padding.bottom);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateTaskStackListenerState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateTaskStackListenerState();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateTaskStackListenerState();
    }

    public void setStateController(RecentsViewStateController stateController) {
        mStateController = stateController;
    }

    public RecentsViewStateController getStateController() {
        return mStateController;
    }

    public void setOverviewStateEnabled(boolean enabled) {
        mOverviewStateEnabled = enabled;
        updateTaskStackListenerState();
    }

    public void update(RecentsTaskLoadPlan loadPlan) {
        final RecentsTaskLoader loader = TouchInteractionService.getRecentsTaskLoader();
        setCurrentPage(0);
        if (getPageAt(mCurrentPage) instanceof TaskView) {
            ((TaskView) getPageAt(mCurrentPage)).setIconScale(0);
        }
        TaskStack stack = loadPlan != null ? loadPlan.getTaskStack() : null;
        if (stack == null) {
            removeAllViews();
            return;
        }

        // Ensure there are as many views as there are tasks in the stack (adding and trimming as
        // necessary)
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final ArrayList<Task> tasks = stack.getTasks();
        setLayoutTransition(null);
        for (int i = getChildCount(); i < tasks.size(); i++) {
            final TaskView taskView = (TaskView) inflater.inflate(R.layout.task, this, false);
            addView(taskView);
        }
        while (getChildCount() > tasks.size()) {
            final TaskView taskView = (TaskView) getChildAt(getChildCount() - 1);
            removeView(taskView);
            loader.unloadTaskData(taskView.getTask());
        }
        setLayoutTransition(mLayoutTransition);

        // Rebind all task views
        for (int i = tasks.size() - 1; i >= 0; i--) {
            final Task task = tasks.get(i);
            final TaskView taskView = (TaskView) getChildAt(tasks.size() - i - 1);
            taskView.bind(task);
            loader.loadTaskData(task);
        }
    }

    public void launchTaskWithId(int taskId) {
        for (int i = 0; i < getChildCount(); i++) {
            final TaskView taskView = (TaskView) getChildAt(i);
            if (taskView.getTask().key.id == taskId) {
                taskView.launchTask(false /* animate */);
                return;
            }
        }
    }

    private void updateTaskStackListenerState() {
        boolean registerStackListener = mOverviewStateEnabled && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE;
        if (registerStackListener != mTaskStackListenerRegistered) {
            if (registerStackListener) {
                ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskStackListener);
            } else {
                ActivityManagerWrapper.getInstance().unregisterTaskStackListener(mTaskStackListener);
            }
            mTaskStackListenerRegistered = registerStackListener;
        }
    }

    private static Rect getPadding(Launcher launcher) {
        DeviceProfile profile = launcher.getDeviceProfile();
        Rect stableInsets = new Rect();
        WindowManagerWrapper.getInstance().getStableInsets(stableInsets);
        Rect padding = profile.getWorkspacePadding(null);
        float taskWidth = profile.getCurrentWidth() - stableInsets.left - stableInsets.right;
        float taskHeight = profile.getCurrentHeight() - stableInsets.top - stableInsets.bottom;
        float overviewHeight = profile.availableHeightPx - padding.top - padding.bottom
                - stableInsets.top;
        float overviewWidth = taskWidth * overviewHeight / taskHeight;
        padding.left = padding.right = (int) ((profile.availableWidthPx - overviewWidth) / 2);
        return padding;
    }

    public static void getPageRect(Launcher launcher, Rect outRect) {
        DragLayer dl = launcher.getDragLayer();
        Rect targetPadding = getPadding(launcher);
        Rect insets = dl.getInsets();
        outRect.set(
                targetPadding.left + insets.left,
                targetPadding.top + insets.top,
                dl.getWidth() - targetPadding.right - insets.right,
                dl.getHeight() - targetPadding.bottom - insets.bottom);
        outRect.top += launcher.getResources()
                .getDimensionPixelSize(R.dimen.task_thumbnail_top_margin);
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        updateCurveProperties();
    }

    /**
     * Scales and adjusts translation of adjacent pages as if on a curved carousel.
     */
    private void updateCurveProperties() {
        if (getPageCount() == 0 || getPageAt(0).getMeasuredWidth() == 0) {
            return;
        }
        final int halfScreenWidth = getMeasuredWidth() / 2;
        final int screenCenter = halfScreenWidth + getScrollX();
        final int pageSpacing = getResources().getDimensionPixelSize(R.dimen.recents_page_spacing);
        final int pageCount = getPageCount();
        for (int i = 0; i < pageCount; i++) {
            View page = getPageAt(i);
            int pageWidth = page.getMeasuredWidth();
            int halfPageWidth = pageWidth / 2;
            int pageCenter = page.getLeft() + halfPageWidth;
            float distanceFromScreenCenter = Math.abs(pageCenter - screenCenter);
            float distanceToReachEdge = halfScreenWidth + halfPageWidth + pageSpacing;
            float linearInterpolation = Math.min(1, distanceFromScreenCenter / distanceToReachEdge);
            float curveInterpolation = CURVE_INTERPOLATOR.getInterpolation(linearInterpolation);
            float scale = 1 - curveInterpolation * CURVE_FACTOR;
            page.setScaleX(scale);
            page.setScaleY(scale);
            // Make sure the biggest card (i.e. the one in front) shows on top of the adjacent ones.
            page.setTranslationZ(scale);
            page.setTranslationX((screenCenter - pageCenter) * curveInterpolation * CURVE_FACTOR);
            if (page instanceof TaskView) {
                TaskThumbnailView thumbnail = ((TaskView) page).getThumbnail();
                thumbnail.setDimAlpha(1 - curveInterpolation * MAX_PAGE_SCRIM_ALPHA);
            }
        }
    }

    public void onTaskDismissed(TaskView taskView) {
        ActivityManagerWrapper.getInstance().removeTask(taskView.getTask().key.id);
        removeView(taskView);
        if (getChildCount() == 0) {
            Launcher.getLauncher(getContext()).getStateManager().goToState(LauncherState.NORMAL);
        }
    }
}
