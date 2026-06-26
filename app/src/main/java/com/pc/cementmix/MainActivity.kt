package com.pc.cementmix

import android.graphics.Paint
import android.graphics.Typeface
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
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
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.Date
import android.content.Intent
import androidx.core.content.FileProvider
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
import android.os.Build
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import android.net.ConnectivityManager
import android.net.Network
import org.json.JSONObject


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

    private var useAsh = false
    private var currentLogFile: File? = null
    private var lastLogFile: File? = null
    private var completionNotificationSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCarPlateDropdown()
        setupGradeDropdown()
        setupDefaultValues()
        setupActions()
        setupNetworkCallback()
        startQueueRetryTimer()

        calculate()
        triggerQueueProcessor()
    }

    private fun setupCarPlateDropdown() {
        updateCarPlateAdapter()
    }

    private fun getCarPlatesHistory(): List<String> {
        val raw = recipesPreferences.getString(KEY_CAR_PLATES_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("|").filter { it.isNotEmpty() }
    }

    private fun saveCarPlateToHistory(plate: String) {
        val trimmed = plate.trim()
        if (trimmed.isEmpty()) return
        val current = getCarPlatesHistory().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        if (current.size > 8) {
            current.removeAt(current.size - 1)
        }
        recipesPreferences.edit().putString(KEY_CAR_PLATES_HISTORY, current.joinToString("|")).apply()
        updateCarPlateAdapter()
    }

    private fun updateCarPlateAdapter() {
        val history = getCarPlatesHistory()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, history)
        binding.carPlateInput.setAdapter(adapter)
    }

    private fun setupNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    triggerQueueProcessor()
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

        updateAshToggleButtonUI()
    }

    private fun setupActions() {
        binding.saveRecipeButton.setOnClickListener {
            saveRecipeForCurrentGrade()
        }

        binding.calculateButton.setOnClickListener {
            calculate()
        }

        binding.ashToggleButton.setOnClickListener {
            useAsh = !useAsh
            updateAshToggleButtonUI()
            calculate()
        }

        binding.shareLogButton.setOnClickListener {
            shareLogFile()
        }
    }

    private fun updateAshToggleButtonUI() {
        if (useAsh) {
            binding.ashToggleButton.setBackgroundColor(getColor(R.color.black))
            binding.ashToggleButton.setTextColor(getColor(R.color.white))
            binding.ashToggleButton.setText(R.string.action_with_ash)
            binding.ashToggleButton.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.black))
        } else {
            binding.ashToggleButton.setBackgroundColor(getColor(android.R.color.transparent))
            binding.ashToggleButton.setTextColor(getColor(R.color.text_primary))
            binding.ashToggleButton.setText(R.string.action_without_ash)
            binding.ashToggleButton.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.card_stroke))
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

    private fun isAshBatchIndex(index: Int): Boolean {
        return index == 1 || index == 6 || index == 11 || index == 16
    }

    private fun calculate() {
        clearErrors()

        val carNumber = binding.carPlateInput.text?.toString()?.trim().orEmpty()
        if (carNumber.isNotEmpty()) {
            saveCarPlateToHistory(carNumber)
        }

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
            binding.shareLogButton.visibility = View.GONE
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

        currentPlanItems = buildPlanItems(
            startWeight = safeStartWeight,
            totalCycles = totalCycles,
            cementPerFullBatch = safeCementPerBatch,
            partialVolume = partialVolume,
            partialCement = partialCement
        )
        completionTimes.clear()
        calculationStartedAt = System.currentTimeMillis()
        completionNotificationSent = false

        startLogSession(grade, safeTotalVolume, safeStartWeight)
        binding.shareLogButton.visibility = View.VISIBLE

        renderBatchButtons()

        binding.batchPlanView.text = describePlan(fullBatchCount, partialVolume)
        binding.batchCountHintView.text = getString(R.string.batch_count_formula, totalCycles)
        binding.totalElapsedView.visibility = View.GONE

        val totalCementUsed = currentPlanItems.sumOf { it.cement }
        val totalAshUsed = currentPlanItems.sumOf { it.ash }
        val finalWeight = safeStartWeight - totalCementUsed
        val enoughCement = finalWeight >= 0.0

        val totalCementWithoutAsh = safeCementPerBatch * fullBatchCount + (if (hasPartialBatch) partialCement else 0.0)
        val finalWeightWithoutAsh = safeStartWeight - totalCementWithoutAsh
        val actualRatio = if (totalCementUsed > 0.0) (totalAshUsed / totalCementUsed) * 100.0 else 0.0

        binding.summaryView.text = buildString {
            appendLine("Марка: $grade")
            appendLine("Общий объём: ${format(safeTotalVolume)} м3 (${describePlan(fullBatchCount, partialVolume)})")
            if (useAsh) {
                appendLine("Режим: С ЗОЛОЙ (Цемент -15% / Зола 18%)")
                appendLine("Всего цемента: ${format(totalCementUsed)} кг (без золы: ${format(totalCementWithoutAsh)} кг)")
                val ratioStr = String.format(Locale.getDefault(), "%,.1f", actualRatio)
                appendLine("Всего золы: ${format(totalAshUsed)} кг (факт: $ratioStr% от цем.) (без золы: 0 кг)")
                append("Остаток на весах: ${format(finalWeight)} кг (без золы: ${format(finalWeightWithoutAsh)} кг)")
            } else {
                appendLine("Режим: БЕЗ ЗОЛЫ")
                appendLine("Всего цемента: ${format(totalCementUsed)} кг")
                append("Остаток на весах: ${format(finalWeight)} кг")
            }
            if (currentPlanItems.size > MAX_VISIBLE_BATCHES) {
                appendLine()
                append(getString(R.string.list_overflow_notice))
            }
        }

        binding.statusView.text = if (enoughCement) {
            getString(R.string.status_ready)
        } else {
            getString(R.string.status_not_enough)
        }
        val statusColor = if (enoughCement) R.color.success else R.color.danger
        binding.statusView.setTextColor(getColor(statusColor))

        hideKeyboard()
    }

    private fun buildPlanItems(
        startWeight: Double,
        totalCycles: Int,
        cementPerFullBatch: Double,
        partialVolume: Double,
        partialCement: Double
    ): List<BatchPlanItem> {
        val hasPartialBatch = partialVolume > EPSILON
        val fullBatchCount = totalCycles - if (hasPartialBatch) 1 else 0
        
        val totalCementRaw = if (useAsh) {
            val reducedCementPerFullBatch = cementPerFullBatch * 0.85
            val reducedCementPerPartial = partialCement * 0.85
            reducedCementPerFullBatch * fullBatchCount + (if (hasPartialBatch) reducedCementPerPartial else 0.0)
        } else {
            cementPerFullBatch * fullBatchCount + (if (hasPartialBatch) partialCement else 0.0)
        }
        
        val totalAshRaw = if (useAsh) totalCementRaw * 0.18 else 0.0

        var totalAshVolume = 0.0
        var totalCementVolume = 0.0
        
        for (index in 1..totalCycles) {
            val isPartial = index == totalCycles && hasPartialBatch
            val volume = if (isPartial) partialVolume else FULL_BATCH_VOLUME
            val isAsh = useAsh && isAshBatchIndex(index)
            if (isAsh) {
                totalAshVolume += volume
            } else {
                totalCementVolume += volume
            }
        }

        val items = mutableListOf<BatchPlanItem>()
        var currentWeight = startWeight
        
        var cumAshVolume = 0.0
        var cumCementVolume = 0.0
        var prevCumAshRounded = 0.0
        var prevCumCementRounded = 0.0
        
        for (index in 1..totalCycles) {
            val isPartial = index == totalCycles && hasPartialBatch
            val volume = if (isPartial) partialVolume else FULL_BATCH_VOLUME
            val isAsh = useAsh && isAshBatchIndex(index)
            
            var cementUsed = 0.0
            var ashUsed = 0.0
            
            if (isAsh) {
                if (totalAshVolume > 0.0) {
                    cumAshVolume += volume
                    val cumAshRaw = totalAshRaw * (cumAshVolume / totalAshVolume)
                    val cumAshRounded = roundTo10(cumAshRaw)
                    ashUsed = cumAshRounded - prevCumAshRounded
                    prevCumAshRounded = cumAshRounded
                }
            } else {
                if (totalCementVolume > 0.0) {
                    cumCementVolume += volume
                    val cumCementRaw = totalCementRaw * (cumCementVolume / totalCementVolume)
                    val cumCementRounded = roundTo10(cumCementRaw)
                    cementUsed = cumCementRounded - prevCumCementRounded
                    prevCumCementRounded = cumCementRounded
                }
            }
            
            currentWeight -= cementUsed
            
            items += BatchPlanItem(
                id = index,
                targetWeight = currentWeight,
                volume = volume,
                cumulativeVolume = roundVolume(items.sumOf { it.volume } + volume),
                cement = cementUsed,
                isPartial = isPartial,
                isAsh = isAsh,
                ash = ashUsed
            )
        }

        return items
    }

    private fun renderBatchButtons() {
        binding.batchButtonsContainer.removeAllViews()

        if (shouldShowTwoColumns()) {
            renderBatchButtonsAsTwoColumns()
            return
        }

        val count = currentPlanItems.size
        for (slotIndex in 1..count) {
            val item = currentPlanItems[slotIndex - 1]
            val views = buildBatchItemView(slotIndex, item)
            val wrapper = views.root
            binding.batchButtonsContainer.addView(wrapper)

            bindBatchButton(
                views.button,
                views.weightView,
                views.indexView,
                views.batchMetaView,
                views.volumeMetaView,
                views.timeView,
                item
            )
        }
    }

    private fun renderBatchButtonsAsTwoColumns() {
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                leftMargin = dp(12)
            }
        }

        binding.batchButtonsContainer.addView(leftColumn)
        binding.batchButtonsContainer.addView(rightColumn)

        val count = currentPlanItems.size
        val leftColumnItemsCount = ceil(count / 2.0).toInt()

        for (slotIndex in 1..count) {
            val item = currentPlanItems[slotIndex - 1]
            val views = buildBatchItemView(slotIndex, item, portraitMode = true)
            val targetColumn = if (slotIndex <= leftColumnItemsCount) leftColumn else rightColumn
            targetColumn.addView(views.root)

            bindBatchButton(
                views.button,
                views.weightView,
                views.indexView,
                views.batchMetaView,
                views.volumeMetaView,
                views.timeView,
                item,
                portraitMode = true
            )
        }
    }

    private fun buildBatchItemView(
        slotIndex: Int,
        item: BatchPlanItem,
        portraitMode: Boolean = false
    ): BatchItemViews {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (!portraitMode && slotIndex > 1) topMargin = dp(12)
                if (portraitMode) bottomMargin = dp(12)
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
            isEnabled = true
            setOnClickListener { toggleBatch(item.id) }
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = dp(14)
                rightMargin = dp(14)
            }
        }

        val indexView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(12)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            setTypeface(Typeface.DEFAULT_BOLD)
            includeFontPadding = false
        }

        val innerDetailsContainer = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        val batchMetaView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(Typeface.DEFAULT_BOLD)
            includeFontPadding = false
        }

        val timeView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(Typeface.DEFAULT_BOLD)
            includeFontPadding = false
            visibility = View.GONE
        }

        val volumeMetaView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(Typeface.DEFAULT_BOLD)
            includeFontPadding = false
            visibility = View.GONE
        }

        val textColor = getColor(R.color.white)
        batchMetaView.setTextColor(textColor)
        timeView.setTextColor(textColor)
        volumeMetaView.setTextColor(textColor)

        innerDetailsContainer.addView(batchMetaView)
        innerDetailsContainer.addView(timeView)
        innerDetailsContainer.addView(volumeMetaView)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1).apply {
                weight = 1f
            }
        }

        val weightView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
            }
            text = if (item.isAsh) weightLabel(item.ash) else weightLabel(item.targetWeight)
            setTextColor(getColor(R.color.white))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTypeface(Typeface.DEFAULT_BOLD)
        }

        contentLayout.addView(indexView)
        contentLayout.addView(innerDetailsContainer)
        contentLayout.addView(spacer)
        contentLayout.addView(weightView)

        buttonFrame.addView(button)
        buttonFrame.addView(contentLayout)
        root.addView(buttonFrame)

        return BatchItemViews(root, button, weightView, indexView, batchMetaView, volumeMetaView, timeView)
    }

    private fun shouldShowTwoColumns(): Boolean {
        val smallestWidth = resources.configuration.smallestScreenWidthDp
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return smallestWidth >= 600 || isLandscape
    }

    private fun bindBatchButton(
        button: MaterialButton,
        weightView: TextView,
        indexView: TextView,
        batchMetaView: TextView,
        volumeMetaView: TextView,
        timeView: TextView,
        item: BatchPlanItem,
        portraitMode: Boolean = false
    ) {
        val completedAt = completionTimes[item.id]
        weightView.text = if (item.isAsh) weightLabel(item.ash) else weightLabel(item.targetWeight)
        indexView.text = item.id.toString()
        
        val completedFillColor = if (item.isAsh) getColor(R.color.ash_completed_fill) else getColor(R.color.completed_fill)
        val completedStrokeColor = if (item.isAsh) getColor(R.color.ash_completed_stroke) else getColor(R.color.completed_stroke)
        val normalFillColor = if (item.isAsh) getColor(R.color.black) else getColor(R.color.primary)
        val normalStrokeColor = if (item.isAsh) getColor(R.color.black) else getColor(R.color.primary_dark)
        
        val textColorNormal = getColor(R.color.white)
        val textColorCompleted = if (item.isAsh) getColor(R.color.white) else getColor(R.color.text_primary)

        indexView.setTextColor(if (completedAt != null) textColorCompleted else textColorNormal)

        val typeStr = if (item.isAsh) getString(R.string.label_ash_batch) else getString(R.string.label_cement_batch)
        batchMetaView.text = typeStr

        if (completedAt != null) {
            button.alpha = if (item.isAsh) 0.6f else 0.7f
            button.setBackgroundColor(completedFillColor)
            button.strokeColor = android.content.res.ColorStateList.valueOf(completedStrokeColor)
            
            weightView.paintFlags = weightView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            weightView.setTextColor(textColorCompleted)
            weightView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (portraitMode) 17f else 19f)
            weightView.translationX = 0f
            
            batchMetaView.visibility = View.VISIBLE
            batchMetaView.setTextColor(textColorCompleted)
            
            volumeMetaView.visibility = View.VISIBLE
            volumeMetaView.text = getString(R.string.completed_batch_volume, format(item.cumulativeVolume))
            volumeMetaView.setTextColor(textColorCompleted)

            val previousTime = previousCompletionTime(item.id) ?: calculationStartedAt
            val interval = completedAt - previousTime

            timeView.visibility = View.VISIBLE
            timeView.text = formatDuration(interval)
            timeView.setTextColor(textColorCompleted)
        } else {
            button.alpha = 1f
            button.setBackgroundColor(normalFillColor)
            button.strokeColor = android.content.res.ColorStateList.valueOf(normalStrokeColor)
            
            weightView.paintFlags = weightView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            weightView.setTextColor(getColor(R.color.white))
            weightView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            weightView.translationX = 0f
            
            batchMetaView.visibility = View.VISIBLE
            batchMetaView.setTextColor(textColorNormal)
            
            volumeMetaView.visibility = View.GONE
            volumeMetaView.text = ""
            timeView.visibility = View.GONE
            timeView.text = ""
        }

        updateTotalElapsedView()
    }

    private fun startLogSession(grade: String, totalVolume: Double, startWeight: Double) {
        try {
            val directory = File(filesDir, "Logs")
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val file = File(directory, "замес_$timestamp.txt")
            currentLogFile = file
            lastLogFile = file

            val writer = FileWriter(file, true)
            val bufferedWriter = BufferedWriter(writer)
            
            val formattedDate = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            bufferedWriter.write("=== НАЧАЛО РАСЧЕТА СЕССИИ ===\n")
            bufferedWriter.write("Дата и время: $formattedDate\n")
            bufferedWriter.write("Марка смеси: $grade\n")
            bufferedWriter.write("Общий объем: ${format(totalVolume)} м3\n")
            bufferedWriter.write("Начальный вес на весах: ${format(startWeight)} кг\n")
            if (useAsh) {
                bufferedWriter.write("Режим: С ЗОЛОЙ (18%)\n")
            } else {
                bufferedWriter.write("Режим: БЕЗ ЗОЛЫ\n")
            }
            bufferedWriter.write("--------------------------------------------------\n")
            bufferedWriter.close()
            
            Toast.makeText(this, "Создан лог-файл: ${file.name}", Toast.LENGTH_SHORT).show()

            // Отправка уведомления в ntfy.sh
            val carNumber = binding.carPlateInput.text?.toString()?.trim().orEmpty()
            val ntfyTitle = if (carNumber.isNotEmpty()) "Начало отгрузки: $grade (Авто $carNumber)" else "Начало отгрузки: $grade"
            val ntfyBody = buildString {
                if (carNumber.isNotEmpty()) {
                    append("Автомобиль: $carNumber\n")
                }
                append("Объем: ${format(totalVolume)} м3\n")
                append("Начальный вес: ${format(startWeight)} кг\n")
                append("Режим: ${if (useAsh) "С ЗОЛОЙ (18%)" else "БЕЗ ЗОЛЫ"}")
            }
            sendNtfyNotification(ntfyTitle, ntfyBody)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка создания лога: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logBatchEvent(item: BatchPlanItem, completed: Boolean) {
        val file = currentLogFile ?: return
        try {
            val writer = FileWriter(file, true)
            val bufferedWriter = BufferedWriter(writer)
            val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            
            val typeStr = if (item.isAsh) "ЗОЛА" else "ЦЕМЕНТ"
            val weightStr = if (item.isAsh) format(item.ash) else format(item.cement)
            val statusStr = if (completed) "ВЫПОЛНЕН" else "ОТМЕНЕН"
            
            val logLine = "$timestamp - Замес ${item.id} ($typeStr): $weightStr кг. Статус: $statusStr. Весы цемента: ${format(item.targetWeight)} кг.\n"
            bufferedWriter.write(logLine)
            bufferedWriter.close()

            // Отправка уведомления в ntfy.sh
            val carNumber = binding.carPlateInput.text?.toString()?.trim().orEmpty()
            val ntfyTitle = if (carNumber.isNotEmpty()) "Замес ${item.id} ($typeStr): $statusStr (Авто $carNumber)" else "Замес ${item.id} ($typeStr): $statusStr"
            val ntfyBody = buildString {
                if (carNumber.isNotEmpty()) {
                    append("Автомобиль: $carNumber\n")
                }
                append("Количество: $weightStr кг\n")
                append("Весы цемента: ${format(item.targetWeight)} кг")
            }
            sendNtfyNotification(ntfyTitle, ntfyBody)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun finishLogSession() {
        val file = currentLogFile ?: return
        try {
            val writer = FileWriter(file, true)
            val bufferedWriter = BufferedWriter(writer)
            val timestamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            
            bufferedWriter.write("--------------------------------------------------\n")
            bufferedWriter.write("=== КОНЕЦ СЕССИИ: ВСЕ ЗАМЕСЫ ВЫПОЛНЕНЫ ===\n")
            bufferedWriter.write("Время завершения: $timestamp\n")
            val totalElapsed = completionTimes.values.lastOrNull()?.let { last ->
                last - calculationStartedAt
            } ?: 0L
            bufferedWriter.write("Общее время работы: ${formatDuration(totalElapsed)}\n")
            val lastItem = currentPlanItems.lastOrNull()
            if (lastItem != null) {
                bufferedWriter.write("Конечный остаток на цементных весах: ${format(lastItem.targetWeight)} кг\n")
            }
            bufferedWriter.write("==================================================\n\n")
            bufferedWriter.close()
            
            Toast.makeText(this, "Сессия замесов завершена. Лог-файл закрыт.", Toast.LENGTH_SHORT).show()
            saveLogToDownloads(file)
            currentLogFile = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendCompletionNotification() {
        try {
            val carNumber = binding.carPlateInput.text?.toString()?.trim().orEmpty()
            val ntfyTitle = if (carNumber.isNotEmpty()) "Итог: Авто $carNumber" else "Итог"
            val ntfyBody = buildString {
                val statusText = binding.statusView.text?.toString().orEmpty()
                if (statusText.isNotEmpty()) {
                    append(statusText).append("\n\n")
                }
                append(binding.summaryView.text?.toString().orEmpty())
            }
            sendNtfyNotification(ntfyTitle, ntfyBody)
            completionNotificationSent = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shareLogFile() {
        val fileToShare = lastLogFile ?: currentLogFile
        if (fileToShare == null || !fileToShare.exists()) {
            Toast.makeText(this, "Нет файла лога для отправки", Toast.LENGTH_SHORT).show()
            return
        }

        saveLogToDownloads(fileToShare)

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "com.pc.cementmix.fileprovider",
                fileToShare
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Поделиться логом"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка при отправке: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLogToDownloads(file: File) {
        try {
            val fileName = file.name
            val resolver = contentResolver

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Toast.makeText(this, "Сохранено в Загрузки: $fileName", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Не удалось сохранить в Загрузки", Toast.LENGTH_SHORT).show()
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val destFile = File(downloadsDir, fileName)
                file.inputStream().use { inputStream ->
                    destFile.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                Toast.makeText(this, "Сохранено в Загрузки: ${destFile.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка сохранения в Загрузки: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
        val item = currentPlanItems.firstOrNull { it.id == batchId } ?: return
        val completed: Boolean
        if (completionTimes.containsKey(batchId)) {
            completionTimes.remove(batchId)
            completed = false
            completionNotificationSent = false
        } else {
            completionTimes[batchId] = System.currentTimeMillis()
            completed = true
        }

        logBatchEvent(item, completed)

        val allCompleted = currentPlanItems.isNotEmpty() && currentPlanItems.all { completionTimes.containsKey(it.id) }
        if (allCompleted && !completionNotificationSent) {
            finishLogSession()
            sendCompletionNotification()
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

    private fun roundTo10(value: Double): Double = round(value / 10.0) * 10.0

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

    private fun hideKeyboard() {
        val view = currentFocus ?: binding.root
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private val queueFile by lazy { File(filesDir, "ntfy_queue.txt") }
    private var isProcessingQueue = false

    private fun queueNotification(url: String, title: String, body: String) {
        synchronized(queueFile) {
            try {
                val line = JSONObject().apply {
                    put("url", url)
                    put("title", title)
                    put("body", body)
                }.toString() + "\n"
                queueFile.appendText(line)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        triggerQueueProcessor()
    }

    private fun triggerQueueProcessor() {
        thread {
            synchronized(queueFile) {
                if (isProcessingQueue) return@thread
                isProcessingQueue = true
            }
            try {
                if (!queueFile.exists()) return@thread
                val tempFile = File(filesDir, "ntfy_queue_temp.txt")
                
                val lines = queueFile.readLines()
                val unsentLines = mutableListOf<String>()
                
                for (line in lines) {
                    if (line.isBlank()) continue
                    val obj = JSONObject(line)
                    val url = obj.getString("url")
                    val title = obj.getString("title")
                    val body = obj.getString("body")
                    
                    val success = sendToUrlSync(url, title, body)
                    if (!success) {
                        unsentLines.add(line)
                    }
                }
                
                if (unsentLines.isEmpty()) {
                    queueFile.delete()
                } else {
                    tempFile.writeText(unsentLines.joinToString("\n") + "\n")
                    tempFile.renameTo(queueFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                synchronized(queueFile) {
                    isProcessingQueue = false
                }
            }
        }
    }

    private fun sendToUrlSync(urlStr: String, title: String, body: String): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true
            
            val titleBytes = title.toByteArray(Charsets.UTF_8)
            val base64Title = Base64.encodeToString(titleBytes, Base64.NO_WRAP)
            conn.setRequestProperty("Title", "=?utf-8?B?$base64Title?=")
            
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }
            
            conn.inputStream.use { it.readBytes() }
            conn.responseCode == 200
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun sendNtfyNotification(title: String, body: String) {
        val urls = listOf(
            "https://ntfy.sh/krasnoeibeloe1970",
            "http://109.195.35.231:2586/krasnoeibeloe1970"
        )
        for (url in urls) {
            queueNotification(url, title, body)
        }
    }

    private fun startQueueRetryTimer() {
        thread(isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(60000)
                    triggerQueueProcessor()
                } catch (e: Exception) {
                    // Ignore or exit loop if interrupted
                }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "cement_mix_recipes"
        private const val KEY_LAST_GRADE = "last_grade"
        private const val DEFAULT_GRADE = "М100"
        private const val DEFAULT_CEMENT_PER_FULL_BATCH = 120.0
        private const val FULL_BATCH_VOLUME = 0.5
        private const val EPSILON = 0.0001
        private const val MAX_VISIBLE_BATCHES = 20
        private const val KEY_CAR_PLATES_HISTORY = "car_plates_history"
    }
}

private data class BatchPlanItem(
    val id: Int,
    val targetWeight: Double,
    val volume: Double,
    val cumulativeVolume: Double,
    val cement: Double,
    val isPartial: Boolean,
    val isAsh: Boolean = false,
    val ash: Double = 0.0
)

private data class BatchItemViews(
    val root: LinearLayout,
    val button: MaterialButton,
    val weightView: TextView,
    val indexView: TextView,
    val batchMetaView: TextView,
    val volumeMetaView: TextView,
    val timeView: TextView
)
