package com.badger.trucks.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.*
import com.badger.trucks.ui.theme.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }
private val httpClient = HttpClient()

// Fond du Lac, WI coordinates
private const val LAT = 43.7730
private const val LON = -88.4471

@Composable
fun WeatherScreen() {
    var tab by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<OpenMeteoCurrent?>(null) }
    var hourly by remember { mutableStateOf<OpenMeteoHourly?>(null) }
    var minutely by remember { mutableStateOf<OpenMeteoMinutely?>(null) }
    var rules by remember { mutableStateOf<List<WeatherRule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var doorAction by remember { mutableStateOf("open") }
    var triggeringRule by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun loadWeather() {
        scope.launch {
            try {
                // Current weather
                val curText = httpClient.get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                    "&current=temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature," +
                    "wind_speed_10m,wind_direction_10m,weather_code,surface_pressure" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=America/Chicago"
                ).bodyAsText()
                val curResp = json.decodeFromString<OpenMeteoCurrentResponse>(curText)
                current = curResp.current

                // Hourly
                val hourText = httpClient.get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                    "&hourly=temperature_2m,relative_humidity_2m,dew_point_2m,apparent_temperature," +
                    "precipitation_probability,weather_code,wind_speed_10m" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=America/Chicago&forecast_hours=24"
                ).bodyAsText()
                val hourResp = json.decodeFromString<OpenMeteoHourlyResponse>(hourText)
                hourly = hourResp.hourly

                // Minutely
                val minText = httpClient.get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                    "&minutely_15=precipitation,weather_code&timezone=America/Chicago&forecast_minutely_15=8"
                ).bodyAsText()
                val minResp = json.decodeFromString<OpenMeteoMinutelyResponse>(minText)
                minutely = minResp.minutely_15

                // Weather rules from Supabase (admin-configured)
                rules = BadgerRepo.getWeatherRules()

                // Evaluate door status
                val c = curResp.current
                if (c != null) {
                    val dewPt = Math.round(c.dew_point_2m).toInt()
                    val temp = Math.round(c.temperature_2m).toInt()
                    var action = "open"
                    var trigger = ""
                    for (rule in rules.filter { it.isActive }) {
                        val triggered = when (rule.ruleType) {
                            "dew_point_min" -> dewPt >= rule.threshold
                            "dew_point_max" -> dewPt <= rule.threshold
                            "temp_min" -> temp >= rule.threshold
                            "temp_max" -> temp <= rule.threshold
                            else -> false
                        }
                        if (triggered) {
                            action = rule.doorAction
                            trigger = rule.description ?: rule.ruleName
                            break
                        }
                    }
                    doorAction = action
                    triggeringRule = trigger
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        loadWeather()
        // Auto-refresh every 5 minutes
        while (true) {
            delay(5 * 60 * 1000L)
            loadWeather()
        }
    }

    val doorsOpen = doorAction == "open"
    val tabs = listOf("Current", "Minute Cast", "Hourly", "Door Status")

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(DarkBg).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Door status banner
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (doorsOpen) Green500.copy(alpha = 0.12f) else Red500.copy(alpha = 0.12f),
                border = CardDefaults.outlinedCardBorder().copy(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (doorsOpen) "🟢" else "🔴", fontSize = 28.sp)
                        Column {
                            Text(
                                "DOORS ${if (doorsOpen) "OPEN" else "CLOSED"}",
                                fontWeight = FontWeight.ExtraBold, fontSize = 16.sp,
                                color = if (doorsOpen) Green400 else Red500
                            )
                            Text(triggeringRule, fontSize = 10.sp, color = MutedText)
                        }
                    }
                    current?.let { c ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Dew: ${Math.round(c.dew_point_2m)}°",
                                color = if (Math.round(c.dew_point_2m) >= 51) Red500 else Green400,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Temp: ${Math.round(c.temperature_2m)}°F",
                                color = Amber500, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Tab selector
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DarkSurface).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                tabs.forEachIndexed { idx, label ->
                    val selected = tab == idx
                    val bgColor = when {
                        !selected -> Color.Transparent
                        idx == 3 -> if (doorsOpen) Green500 else Red500
                        else -> Amber500
                    }
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .clickable { tab = idx }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (selected) Color.Black else MutedText,
                        )
                    }
                }
            }
        }

        // Tab content
        when (tab) {
            0 -> current?.let { c -> item { CurrentContent(c) } }
            1 -> minutely?.let { m -> item { MinutecastContent(m) } }
            2 -> hourly?.let { h ->
                itemsIndexed(h.time.take(24).toList()) { idx, time ->
                    HourlyRow(idx, time, h)
                }
            }
            3 -> item { DoorStatusContent(current, rules, doorAction, triggeringRule) }
        }
    }
}

// ── Current Weather ──────────────────────────────────────────
@Composable
fun CurrentContent(c: OpenMeteoCurrent) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Big temp card
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(weatherCodeToIcon(c.weather_code), fontSize = 48.sp)
                Spacer(Modifier.height(4.dp))
                Text("${Math.round(c.temperature_2m)}°", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, color = LightText)
                Text("F", fontSize = 20.sp, color = MutedText)
                Text(weatherCodeToText(c.weather_code), fontSize = 16.sp, color = MutedText)
                Spacer(Modifier.height(4.dp))
                Text("Feels like ${Math.round(c.apparent_temperature)}°F", fontSize = 13.sp, color = MutedText)
            }
        }

        // Detail grid
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeatherDetailCard("💧", "Humidity", "${Math.round(c.relative_humidity_2m)}%", Modifier.weight(1f))
            val dewPt = Math.round(c.dew_point_2m).toInt()
            WeatherDetailCard("🌡️", "Dew Point", "${dewPt}°F", Modifier.weight(1f),
                highlight = if (dewPt >= 51) Red500 else Green400)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WeatherDetailCard("💨", "Wind", "${Math.round(c.wind_speed_10m)} mph ${degToDir(c.wind_direction_10m)}", Modifier.weight(1f))
            WeatherDetailCard("📊", "Pressure", "${"%.2f".format(c.surface_pressure * 0.02953)} in", Modifier.weight(1f))
        }

        Text("Fond du Lac, WI 54935", fontSize = 11.sp, color = MutedText.copy(alpha = 0.5f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun WeatherDetailCard(icon: String, label: String, value: String, modifier: Modifier, highlight: Color? = null) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (highlight != null) highlight.copy(alpha = 0.06f) else DarkSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(label, fontSize = 9.sp, color = MutedText, fontWeight = FontWeight.Bold)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = highlight ?: LightText)
        }
    }
}

// ── Minutecast ───────────────────────────────────────────────
@Composable
fun MinutecastContent(m: OpenMeteoMinutely) {
    val hasRain = m.precipitation.any { it > 0 }
    val maxPrecip = m.precipitation.maxOrNull()?.coerceAtLeast(0.1) ?: 0.1

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Precipitation — Next 2 Hours", fontWeight = FontWeight.Bold, color = LightText)
            Text(
                if (hasRain) "Precipitation expected" else "No precipitation expected",
                fontSize = 12.sp, color = MutedText
            )
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                m.precipitation.forEachIndexed { idx, precip ->
                    val height = ((precip / maxPrecip) * 100).coerceAtLeast(2.0)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().height(height.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (precip > 0) Blue500 else DarkBorder)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                m.time.forEachIndexed { idx, time ->
                    if (idx % 2 == 0) {
                        val hour = time.substringAfter("T").take(5)
                        Text(hour, fontSize = 8.sp, color = MutedText)
                    }
                }
            }
        }
    }
}

// ── Hourly Row ───────────────────────────────────────────────
@Composable
fun HourlyRow(idx: Int, time: String, h: OpenMeteoHourly) {
    val isNow = idx == 0
    val dewPt = Math.round(h.dew_point_2m[idx]).toInt()
    val hour = time.substringAfter("T").take(5)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isNow) Amber500.copy(alpha = 0.08f) else DarkSurface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(weatherCodeToIcon(h.weather_code[idx]), fontSize = 18.sp, modifier = Modifier.width(28.dp))
            Text(if (isNow) "Now" else hour, fontSize = 12.sp, color = MutedText, fontWeight = FontWeight.Medium, modifier = Modifier.width(42.dp))
            Text("${Math.round(h.temperature_2m[idx])}°", fontWeight = FontWeight.Bold, color = LightText, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("💧${Math.round(h.relative_humidity_2m[idx])}%", fontSize = 10.sp, color = Blue400)
                    Text("🌡️${dewPt}°", fontSize = 10.sp, color = if (dewPt >= 51) Red500 else Green400, fontWeight = FontWeight.Bold)
                    Text("🌧️${h.precipitation_probability[idx]}%", fontSize = 10.sp, color = Blue400)
                }
            }
            Text("${Math.round(h.wind_speed_10m[idx])}mph", fontSize = 10.sp, color = MutedText)
        }
    }
}

// ── Door Status ──────────────────────────────────────────────
@Composable
fun DoorStatusContent(current: OpenMeteoCurrent?, rules: List<WeatherRule>, doorAction: String, triggeringRule: String) {
    val isOpen = doorAction == "open"
    val dewPt = current?.let { Math.round(it.dew_point_2m).toInt() } ?: 0
    val temp = current?.let { Math.round(it.temperature_2m).toInt() } ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Big status
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isOpen) Green500.copy(alpha = 0.08f) else Red500.copy(alpha = 0.08f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(32.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (isOpen) "✅" else "🚫", fontSize = 56.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "DOORS ${if (isOpen) "OPEN" else "CLOSED"}",
                    fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (isOpen) Green400 else Red500
                )
                Spacer(Modifier.height(4.dp))
                Text(triggeringRule, fontSize = 13.sp, color = MutedText)
            }
        }

        // Current readings
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (dewPt >= 51) Red500.copy(alpha = 0.06f) else Green500.copy(alpha = 0.06f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Dew Point", fontSize = 10.sp, color = MutedText, fontWeight = FontWeight.Bold)
                    Text("${dewPt}°F", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (dewPt >= 51) Red500 else Green400)
                }
            }
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Temperature", fontSize = 10.sp, color = MutedText, fontWeight = FontWeight.Bold)
                    Text("${temp}°F", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Amber500)
                }
            }
        }

        // Rules list
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column {
                Text("Active Rules", fontWeight = FontWeight.Bold, color = LightText,
                    modifier = Modifier.padding(12.dp))
                Divider(color = DarkBorder)
                rules.filter { it.isActive }.forEach { rule ->
                    val triggered = when (rule.ruleType) {
                        "dew_point_min" -> dewPt >= rule.threshold
                        "dew_point_max" -> dewPt <= rule.threshold
                        "temp_min" -> temp >= rule.threshold
                        "temp_max" -> temp <= rule.threshold
                        else -> false
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (triggered) Amber500.copy(alpha = 0.05f) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (triggered) Text("⚡", fontSize = 12.sp)
                            Column {
                                Text(rule.description ?: rule.ruleName, fontSize = 13.sp, color = LightText, fontWeight = FontWeight.Medium)
                                Text("Priority: ${rule.priority}", fontSize = 10.sp, color = MutedText)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (rule.doorAction == "open") Green500.copy(alpha = 0.15f) else Red500.copy(alpha = 0.15f)
                        ) {
                            Text(
                                if (rule.doorAction == "open") "OPEN" else "CLOSE",
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                color = if (rule.doorAction == "open") Green400 else Red500
                            )
                        }
                    }
                    Divider(color = DarkBorder.copy(alpha = 0.3f))
                }
            }
        }

        Text("Rules managed in Admin → Weather Rules", fontSize = 10.sp, color = MutedText.copy(alpha = 0.4f),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
