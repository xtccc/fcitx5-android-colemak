/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import androidx.annotation.CallSuper
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.CandidatesPagingSource
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.VerticalDividerDecoration
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AlwaysFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.AutoFillWidth
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateMode.NeverFillWidth
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {

    companion object {
        /**
         * Candidates loaded per page. A single screen usually shows about this many
         * wrap-content candidates, so the first page fills up the screen and the rest
         * are loaded on demand when scrolling.
         */
        const val CANDIDATES_PAGE_SIZE = 8
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val service by manager.inputMethodService()
    private val bar: KawaiiBarComponent by manager.must()

    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount
            else
                expandedCandidateGridSpanCountLandscape
        }
    }

    private var layoutMinWidth = 0

    // Since expanded candidate window is created once the expand button was clicked,
    // we need to replay the last offset
    private val _expandedCandidateOffset = MutableSharedFlow<Int>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expandedCandidateOffset = _expandedCandidateOffset.asSharedFlow()

    private fun refreshExpanded(childCount: Int) {
        _expandedCandidateOffset.tryEmit(childCount)
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to (adapter.total == childCount)
        )
    }

    /**
     * Set to true on every candidate update, cleared when the refreshed list is actually
     * presented to the [RecyclerView]. We scroll back to the beginning only after the new
     * page has been presented, otherwise `scrollToPosition(0)` races with the async
     * `submitData` replacement and the old scroll offset ends up somewhere inside the
     * newly presented list.
     */
    private var resetScrollPending = false

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            @CallSuper
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.minimumWidth = layoutMinWidth
                holder.itemView.setOnClickListener {
                    // scroll back to the beginning right away, so the next candidate list
                    // starts from the first page instead of the previous scroll position
                    layoutManager.scrollToPosition(0)
                    fcitx.launchOnReady { it.select(holder.idx) }
                }
                holder.itemView.setOnLongClickListener {
                    inputView.showCandidateActionMenu(holder.idx, holder.candidate.text, holder.ui.root)
                    true
                }
            }

            override fun onViewRecycled(holder: CandidateViewHolder) {
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                super.onViewRecycled(holder)
            }
        }
    }

    val layoutManager: LinearLayoutManager by lazy {
        object : LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false) {
            override fun canScrollVertically() = false
            override fun canScrollHorizontally() = true
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                refreshExpanded(this.childCount)
            }
        }
    }

    private val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    override val view by lazy {
        object : RecyclerView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (fillStyle == AutoFillWidth) {
                    val maxSpanCount = maxSpanCountPref.getValue()
                    layoutMinWidth = w / maxSpanCount - dividerDrawable.intrinsicWidth
                }
            }
        }.apply {
            id = R.id.candidate_view
            itemAnimator = null
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            addItemDecoration(VerticalDividerDecoration(dividerDrawable))
        }
    }

    private var pagingSubmitJob: Job? = null

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        val candidates = data.candidates
        val total = data.total
        val maxSpanCount = maxSpanCountPref.getValue()
        when (fillStyle) {
            NeverFillWidth -> {
                layoutMinWidth = 0
            }
            AutoFillWidth -> {
                layoutMinWidth = view.width / maxSpanCount - dividerDrawable.intrinsicWidth
            }
            AlwaysFillWidth -> {
                layoutMinWidth = 0
            }
        }
        adapter.updateTotal(total)
        // scroll back to the beginning right away, so the old candidate list doesn't
        // linger at the previous scroll position while the refreshed list is loading.
        // `resetScrollPending` below re-asserts the position after the new list is presented.
        layoutManager.scrollToPosition(0)
        // ask to scroll back once the refreshed list is presented (see resetScrollPending)
        resetScrollPending = true
        pagingSubmitJob?.cancel()
        pagingSubmitJob = service.lifecycleScope.launch {
            Pager(
                config = PagingConfig(
                    pageSize = CANDIDATES_PAGE_SIZE,
                    // only load the first screen initially, then load more on scroll
                    initialLoadSize = CANDIDATES_PAGE_SIZE,
                    // Load a small batch ahead of time for smooth scrolling but avoid
                    // fetching the whole candidate list at once.
                    prefetchDistance = CANDIDATES_PAGE_SIZE / 2,
                    // Keep all loaded pages: evicting pages would shift adapter positions
                    // away from global candidate indices, making `select(holder.idx)` pick
                    // the wrong candidate once the user scrolls past the limit.
                    maxSize = PagingConfig.MAX_SIZE_UNBOUNDED,
                    enablePlaceholders = false
                ),
                pagingSourceFactory = {
                    CandidatesPagingSource(fcitx, total = total, offset = 0)
                }
            ).flow.collectLatest { data ->
                // `submitData` suspends until the list is actually presented, so scrolling
                // afterwards lands on the head of the new candidates instead of racing with
                // the async replacement (which confused the scroll offset before).
                adapter.submitData(data)
                if (resetScrollPending) {
                    resetScrollPending = false
                    layoutManager.scrollToPosition(0)
                }
            }
        }
        // not sure why empty candidates won't trigger `LinearLayoutManager#onLayoutCompleted()`
        if (candidates.isEmpty()) {
            refreshExpanded(0)
        }
    }
}