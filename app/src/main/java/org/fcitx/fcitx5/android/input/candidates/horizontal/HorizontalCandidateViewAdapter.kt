/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) :
    PagingDataAdapter<CandidateWord, CandidateViewHolder>(diffCallback) {

    companion object {
        /**
         * Always re-bind all [CandidateViewHolder]s every time to make sure `idx` is up-to-date.
         * [CandidateViewHolder.update] would skip unnecessary UI updates.
         */
        private val diffCallback = object : DiffUtil.ItemCallback<CandidateWord>() {
            override fun areItemsTheSame(oldItem: CandidateWord, newItem: CandidateWord) = false
            override fun areContentsTheSame(oldItem: CandidateWord, newItem: CandidateWord) = false
        }
    }

    var total = -1
        private set

    fun updateTotal(total: Int) {
        this.total = total
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(10, 0, 10, 0)
            layoutParams = RecyclerView.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val candidate = getItem(position) ?: CandidateWord.Empty
        holder.update(position, candidate)
    }

    @CallSuper
    override fun onViewRecycled(holder: CandidateViewHolder) {
        holder.clear()
    }

}