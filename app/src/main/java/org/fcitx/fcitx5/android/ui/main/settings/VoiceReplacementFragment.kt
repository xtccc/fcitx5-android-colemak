/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.voicereplacement.VoiceReplacement
import org.fcitx.fcitx5.android.data.voicereplacement.VoiceReplacementManager
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode

/**
 * Editor for the voice input replacement dictionary.
 *
 * Every entry rewrites one phrase of the transcript after the cloud ASR has
 * produced it, which is how homophones that the engine keeps getting wrong
 * (e.g. 诸葛 vs 猪哥) get fixed.
 */
class VoiceReplacementFragment : Fragment(), OnItemChangedListener<VoiceReplacement> {

    private val viewModel: MainViewModel by activityViewModels()

    private val ui: BaseDynamicListUi<VoiceReplacement> by lazy {
        object : BaseDynamicListUi<VoiceReplacement>(
            requireContext(),
            BaseDynamicListUi.Mode.FreeAdd(
                hint = getString(R.string.voice_replacement_hint),
                converter = { VoiceReplacement.parse(it)!! },
                validator = { VoiceReplacement.parse(it) != null },
            ),
            VoiceReplacementManager.list(),
            initCheckBox = { entry ->
                isEnabled = true
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, checked ->
                    ui.updateItem(ui.indexItem(entry), entry.copy(enabled = checked))
                }
            },
        ) {
            init {
                enableUndo = false
                setViewModel(viewModel)
                addTouchCallback()
            }

            override fun showEntry(x: VoiceReplacement): String = x.serialize()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ui.addOnItemChangedListener(this)
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateToolbarButton()
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
    }

    override fun onItemAdded(idx: Int, item: VoiceReplacement) = persist()

    override fun onItemRemoved(idx: Int, item: VoiceReplacement) = persist()

    override fun onItemUpdated(idx: Int, old: VoiceReplacement, new: VoiceReplacement) = persist()

    override fun onItemRemovedBatch(indexed: List<Pair<Int, VoiceReplacement>>) = persist()

    private fun persist() {
        VoiceReplacementManager.save(ui.entries)
        updateToolbarButton()
    }

    private fun updateToolbarButton() {
        viewModel.toolbarButton.value = if (ui.entries.isEmpty()) ButtonMode.NONE else ButtonMode.EDIT
    }
}
