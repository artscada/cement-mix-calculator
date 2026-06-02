package com.pc.cementmix

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.pc.cementmix.databinding.ActivityMainBinding
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val numberFormat = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale("ru", "RU")))
    private val weightFormat = DecimalFormat("#,##0", DecimalFormatSymbols(Locale("ru", "RU")))
    private val grades = listOf("М100", "М150", "М200", "М250", "М300", "М350", "М400", "Р100", "Р150")
    private val defaultRecipes = mapOf("М100" to 120.0)

    private val recipesPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var currentPlanItems: List<BatchPlanItem> = emptyList()
    private val completionTimes = linkedMapOf<Int, Long>()
    private var calculationStartedAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGradeDropdown()
        setupDefaultValues()
        setupActions()

        calculate()
    }

    private fun setupGradeDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, grades)
        binding.gradeInput.setAdapter(adapter)
        binding.gradeInput.setOnItemClickListener { _, _, position, _ ->
            val grade = grades[position]
            saveLastGrade(grade)
            applySavedRecipeForGrade(grade)
            calculate()
        }
    }

    private fun setupDefaultValues() {
        binding.totalVolumeInput.setText("5")
        binding.startWeightInput.setText("10850")

        val initialGrade = savedLastGrade().takeIf { it in grades } ?: DEFAULT_GRADE
        binding.gradeInput.setText(initialGrade, false)
        applySavedRecipeForGrade(initialGrade)

        if (binding.cementPerBatchInput.text.isNullOrBlank()) {
            binding.cementPerBatchInput.setText(formatEditable(DEFAULT_CEMENT_PER_FULL_BATCH))
        }
    }

    private fun setupActions() {
        binding.saveRecipeButton.setOnClickListener {
            saveRecipeForCurrentGrade()
        }

        binding.calculateButton.setOnClickListener {
            calculate()
        }
    }

    private fun saveRecipeForCurrentGrade() {
        val grade = selectedGrade()
        val cementPerBatch = parseDouble(binding.cementPerBatchInput.text?.toString())

        if (cementPerBatch == null || cementPerBatch <= 0.0) {
            binding.cementPerBatchLayout.error = getString(R.string.error_positive_number)
            return
        }

        binding.cementPerBatchLayout.error = null
        recipesPreferences.edit()
            .putString(recipeKey(grade), cementPerBatch.toString())
            .apply()
        saveLastGrade(grade)

        Toast.makeText(
            this,
            getString(R.string.recipe_saved, grade, format(cementPerBatch)),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applySavedRecipeForGrade(grade: String) {
        val storedRecipe = loadRecipeForGrade(grade) ?: defaultRecipes[grade] ?: return
        binding.cementPerBatchInput.setText(formatEditable(storedRecipe))
    }

    private fun loadRecipeForGrade(grade: String): Double? {
        return recipesPreferences.getString(recipeKey(grade), null)?.toDoubleOrNull()
    }

    private fun calculate() {
        clearErrors()

        val grade = selectedGrade()
        val cementPerBatch = parseDouble(binding.cementPerBatchInput.text?.toString())
        val totalVolume = parseDouble(binding.totalVolumeInput.text?.toString())
        val startWeight = parseDouble(binding.startWeightInput.text?.toString())

        var hasError = false

        if (cementPerBatch == null || cementPerBatch <= 0.0) {
            binding.cementPerBatchLayout.error = getString(R.string.error_positive_number)
            hasError = true
        }

        if (totalVolume == null || totalVolume <= 0.0) {
            binding.totalVolumeLayout.error = getString(R.string.error_positive_number)
            hasError = true
        }

        if (startWeight == null || startWeight < 0.0) {
            binding.startWeightLayout.error = getString(R.string.error_non_negative_number)
            hasError = true
        }

        if (hasError) {
            currentPlanItems = emptyList()
            completionTimes.clear()
            renderBatchButtons()
            binding.batchPlanView.text = getString(R.string.batch_count_hint_default)
            binding.batchCountHintView.text = getString(R.string.batch_count_formula_default)
            binding.totalElapsedView.visibility = View.GONE
            binding.summaryView.text = getString(R.string.summary_waiting)
            return
        }

        val safeCementPerBatch = ceilTo10(cementPerBatch!!)
        val safeTotalVolume = roundVolume(totalVolume!!)
        val safeStartWeight = startWeight!!

        val fullBatchCount = floor((safeTotalVolume / FULL_BATCH_VOLUME) + EPSILON).toInt()
        val partialVolume = normalizePartialVolume(safeTotalVolume - fullBatchCount * FULL_BATCH_VOLUME)
        val hasPartialBatch = partialVolume > EPSILON
        val partialCement = if (hasPartialBatch) {
            ceilTo10(safeCementPerBatch * (partialVolume / FULL_BATCH_VOLUME))
        } else {
            0.0
        }
        val totalCycles = fullBatchCount + if (hasPartialBatch) 1 else 0
        val totalCement = safeCementPerBatch * fullBatchCount + partialCement
        val finalWeight = safeStartWeight - totalCement
        val enoughCement = finalWeight >= 0.0

        currentPlanItems = buildPlanItems(
            startWeight = safeStartWeight,
            fullBatchCount = fullBatchCount,
            cementPerFullBatch = safeCementPerBatch,
            partialVolume = partialVolume,
            partialCement = partialCement
        )
        completionTimes.clear()
        calculationStartedAt = System.currentTimeMillis()
        renderBatchButtons()

        binding.batchPlanView.text = describePlan(fullBatchCount, partialVolume)
        binding.batchCountHintView.text = getString(R.string.batch_count_formula, totalCycles)
        binding.totalElapsedView.visibility = View.GONE

        binding.summaryView.text = buildString {
            appendLine("Марка: $grade")
            appendLine("Полный замес 0,5 м3: ${format(safeCementPerBatch)} кг цемента")
            appendLine("Общий объём: ${format(safeTotalVolume)} м3")
            appendLine("План замесов: ${describePlan(fullBatchCount, partialVolume)}")
            if (hasPartialBatch) {
                appendLine("Догруз: ${format(partialVolume)} м3 = ${format(partialCement)} кг")
            }
            appendLine("Всего циклов: $totalCycles")
            appendLine("Всего цемента нужно: ${format(totalCement)} кг")
            append("Остаток после последнего замеса: ${format(finalWeight)} кг")
        }

        binding.statusView.text = if (enoughCement) {
            getString(R.string.status_ready)
        } else {
            getString(R.string.status_not_enough)
        }
        val statusColor = if (enoughCement) R.color.success else R.color.danger
        binding.statusView.setTextColor(getColor(statusColor))
    }

    private fun buildPlanItems(
        startWeight: Double,
        fullBatchCount: Int,
        cementPerFullBatch: Double,
        partialVolume: Double,
        partialCement: Double
    ): List<BatchPlanItem> {
        val items = mutableListOf<BatchPlanItem>()
        var currentWeight = startWeight

        for (index in 1..fullBatchCount) {
            currentWeight -= cementPerFullBatch
            items += BatchPlanItem(
                id = index,
                targetWeight = currentWeight,
                volume = FULL_BATCH_VOLUME,
                cumulativeVolume = roundVolume(index * FULL_BATCH_VOLUME),
                cement = cementPerFullBatch,
                isPartial = false
            )
        }

        if (partialVolume > EPSILON) {
            currentWeight -= partialCement
            items += BatchPlanItem(
                id = items.size + 1,
                targetWeight = currentWeight,
                volume = partialVolume,
                cumulativeVolume = roundVolume(fullBatchCount * FULL_BATCH_VOLUME + partialVolume),
                cement = partialCement,
                isPartial = true
            )
        }

        return items
    }

    private fun renderBatchButtons() {
        binding.batchButtonsContainer.removeAllViews()

        if (currentPlanItems.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = getString(R.string.list_waiting)
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            binding.batchButtonsContainer.addView(emptyView)
            return
        }

        currentPlanItems.forEachIndexed { index, item ->
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (index > 0) topMargin = dp(12)
                }
            }

            val buttonFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(76)
                )
            }

            val button = MaterialButton(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                text = ""
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(20)
                strokeWidth = dp(1)
                setOnClickListener { toggleBatch(item.id) }
            }

            val weightView = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                text = weightLabel(item.targetWeight)
                setTextColor(getColor(R.color.white))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                setTypeface(Typeface.DEFAULT_BOLD)
                translationX = dp(34).toFloat()
            }

            val batchMetaView = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                ).apply {
                    leftMargin = dp(12)
                    topMargin = dp(8)
                }
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(Typeface.DEFAULT_BOLD)
                setLineSpacing(1f, 1f)
                visibility = View.GONE
            }

            val volumeMetaView = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dp(8)
                }
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(Typeface.DEFAULT_BOLD)
                visibility = View.GONE
            }

            val timeView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    leftMargin = dp(6)
                }
                setTextColor(getColor(R.color.text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                visibility = View.GONE
            }

            buttonFrame.addView(button)
            buttonFrame.addView(weightView)
            buttonFrame.addView(batchMetaView)
            buttonFrame.addView(volumeMetaView)
            wrapper.addView(buttonFrame)
            wrapper.addView(timeView)
            binding.batchButtonsContainer.addView(wrapper)

            bindBatchButton(button, weightView, batchMetaView, volumeMetaView, timeView, item)
        }
    }

    private fun bindBatchButton(
        button: MaterialButton,
        weightView: TextView,
        batchMetaView: TextView,
        volumeMetaView: TextView,
        timeView: TextView,
        item: BatchPlanItem
    ) {
        val completedAt = completionTimes[item.id]
        if (completedAt != null) {
            button.alpha = 0.7f
            button.setBackgroundColor(getColor(R.color.completed_fill))
            button.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.completed_stroke))
            weightView.paintFlags = weightView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            weightView.setTextColor(getColor(R.color.text_primary))
            batchMetaView.visibility = View.VISIBLE
            batchMetaView.text = getString(
                R.string.completed_batch_label,
                item.id
            )
            volumeMetaView.visibility = View.VISIBLE
            volumeMetaView.text = getString(R.string.completed_batch_volume, format(item.cumulativeVolume))

            val previousTime = previousCompletionTime(item.id) ?: calculationStartedAt
            val interval = completedAt - previousTime
            val isLatestCompleted = completionTimes.keys.lastOrNull() == item.id

            timeView.visibility = View.VISIBLE
            timeView.text = buildString {
                append(
                    if (previousCompletionTime(item.id) == null) {
                        getString(R.string.elapsed_from_calculation, formatDuration(interval))
                    } else {
                        getString(R.string.elapsed_from_previous, formatDuration(interval))
                    }
                )
                if (isLatestCompleted) {
                    appendLine()
                    append(getString(R.string.elapsed_total, formatDuration(completedAt - calculationStartedAt)))
                }
            }
        } else {
            button.alpha = 1f
            button.setBackgroundColor(getColor(R.color.primary))
            button.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.primary_dark))
            weightView.paintFlags = weightView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            weightView.setTextColor(getColor(R.color.white))
            batchMetaView.visibility = View.GONE
            batchMetaView.text = ""
            volumeMetaView.visibility = View.GONE
            volumeMetaView.text = ""
            timeView.visibility = View.GONE
            timeView.text = ""
        }

        updateTotalElapsedView()
    }

    private fun updateTotalElapsedView() {
        val lastCompletion = completionTimes.values.lastOrNull()
        if (lastCompletion == null || calculationStartedAt == 0L) {
            binding.totalElapsedView.visibility = View.GONE
            binding.totalElapsedView.text = ""
            return
        }

        binding.totalElapsedView.visibility = View.VISIBLE
        binding.totalElapsedView.text = getString(
            R.string.total_elapsed_footer,
            formatDuration(lastCompletion - calculationStartedAt)
        )
    }

    private fun toggleBatch(batchId: Int) {
        if (completionTimes.containsKey(batchId)) {
            completionTimes.remove(batchId)
        } else {
            completionTimes[batchId] = System.currentTimeMillis()
        }
        renderBatchButtons()
    }

    private fun previousCompletionTime(batchId: Int): Long? {
        val previousIds = completionTimes.keys.filter { it < batchId }
        val previousId = previousIds.maxOrNull() ?: return null
        return completionTimes[previousId]
    }

    private fun describePlan(fullBatchCount: Int, partialVolume: Double): String {
        return when {
            fullBatchCount > 0 && partialVolume > EPSILON -> {
                getString(R.string.batch_plan_with_partial, fullBatchCount, format(partialVolume))
            }

            fullBatchCount > 0 -> {
                getString(R.string.batch_plan_full_only, fullBatchCount)
            }

            else -> {
                getString(R.string.batch_plan_partial_only, format(partialVolume))
            }
        }
    }

    private fun weightLabel(value: Double): String = weightFormat.format(value)

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun clearErrors() {
        binding.cementPerBatchLayout.error = null
        binding.totalVolumeLayout.error = null
        binding.startWeightLayout.error = null
    }

    private fun parseDouble(raw: String?): Double? {
        val normalized = raw
            ?.trim()
            ?.replace(" ", "")
            ?.replace(',', '.')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return normalized.toDoubleOrNull()
    }

    private fun normalizePartialVolume(value: Double): Double {
        return if (value < EPSILON) 0.0 else roundVolume(value)
    }

    private fun roundVolume(value: Double): Double = round(value * 100.0) / 100.0

    private fun ceilTo10(value: Double): Double = ceil(value / 10.0) * 10.0

    private fun selectedGrade(): String {
        val raw = binding.gradeInput.text?.toString()?.trim().orEmpty()
        return raw.takeIf { it in grades } ?: DEFAULT_GRADE
    }

    private fun saveLastGrade(grade: String) {
        recipesPreferences.edit().putString(KEY_LAST_GRADE, grade).apply()
    }

    private fun savedLastGrade(): String? = recipesPreferences.getString(KEY_LAST_GRADE, null)

    private fun recipeKey(grade: String): String = "recipe_${grades.indexOf(grade)}"

    private fun format(value: Double): String = numberFormat.format(value)

    private fun formatEditable(value: Double): String {
        val normalized = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
        return normalized.replace('.', ',')
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        private const val PREFS_NAME = "cement_mix_recipes"
        private const val KEY_LAST_GRADE = "last_grade"
        private const val DEFAULT_GRADE = "М100"
        private const val DEFAULT_CEMENT_PER_FULL_BATCH = 120.0
        private const val FULL_BATCH_VOLUME = 0.5
        private const val EPSILON = 0.0001
    }
}

private data class BatchPlanItem(
    val id: Int,
    val targetWeight: Double,
    val volume: Double,
    val cumulativeVolume: Double,
    val cement: Double,
    val isPartial: Boolean
)
