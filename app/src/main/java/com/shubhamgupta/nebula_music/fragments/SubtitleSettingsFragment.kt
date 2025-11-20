package com.shubhamgupta.nebula_music.fragments

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.shubhamgupta.nebula_music.R
import com.shubhamgupta.nebula_music.utils.PreferenceManager

class SubtitleSettingsFragment : BottomSheetDialogFragment() {

    private var callback: (() -> Unit)? = null

    fun setOnSettingsChangedListener(listener: () -> Unit) {
        this.callback = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout

            if (bottomSheet != null) {
                // 1. Fix the background (Transparency)
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)

                // 2. Fix the "Pull upwards" issue (Force Full Expansion)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        return dialog
    }

    override fun onStart() {
        super.onStart()
        // Remove the dark screen shadow (Dim) covering the video
        dialog?.window?.setDimAmount(0f)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_subtitle_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        var settings = PreferenceManager.getSubtitleSettings(context)

        // --- Controls ---
        val seekSize = view.findViewById<SeekBar>(R.id.seek_font_size)
        val rgColor = view.findViewById<RadioGroup>(R.id.rg_text_color)
        val rgOpacity = view.findViewById<RadioGroup>(R.id.rg_opacity)
        val seekPos = view.findViewById<SeekBar>(R.id.seek_position)
        val btnApply = view.findViewById<Button>(R.id.btn_apply)

        // --- Init Values ---
        seekSize.progress = settings.fontSize
        seekPos.progress = (settings.bottomPadding * 100).toInt()

        // Init Color Selection
        when(settings.textColor) {
            Color.WHITE -> rgColor.check(R.id.tc_white)
            Color.YELLOW -> rgColor.check(R.id.tc_yellow)
            Color.CYAN -> rgColor.check(R.id.tc_cyan)
            Color.GREEN -> rgColor.check(R.id.tc_green)
            else -> rgColor.check(R.id.tc_white)
        }

        // Init Opacity Selection
        when(settings.bgOpacity) {
            0 -> rgOpacity.check(R.id.btn_op_0)
            25 -> rgOpacity.check(R.id.btn_op_25)
            50 -> rgOpacity.check(R.id.btn_op_50)
            75 -> rgOpacity.check(R.id.btn_op_75)
            100 -> rgOpacity.check(R.id.btn_op_100)
            else -> rgOpacity.check(R.id.btn_op_0)
        }

        // --- Listeners ---
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { settings = settings.copy(fontSize = p.coerceAtLeast(10)) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { settings = settings.copy(bottomPadding = p / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        rgColor.setOnCheckedChangeListener { _, checkedId ->
            val color = when(checkedId) {
                R.id.tc_yellow -> Color.YELLOW
                R.id.tc_cyan -> Color.CYAN
                R.id.tc_green -> Color.GREEN
                else -> Color.WHITE
            }
            settings = settings.copy(textColor = color)
        }

        rgOpacity.setOnCheckedChangeListener { _, checkedId ->
            val opacity = when(checkedId) {
                R.id.btn_op_25 -> 25
                R.id.btn_op_50 -> 50
                R.id.btn_op_75 -> 75
                R.id.btn_op_100 -> 100
                else -> 0
            }
            settings = settings.copy(bgOpacity = opacity)
        }

        btnApply.setOnClickListener {
            PreferenceManager.saveSubtitleSettings(context, settings)
            callback?.invoke()
            dismiss()
        }
    }
}