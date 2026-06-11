package com.badger.trucks.ui.liveview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.BadgerApp
import com.badger.trucks.ui.theme.*
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive

// ─── Models ───────────────────────────────────────────────────────────────────

@Serializable
data class DriverRoute(
    val id: Int = 0,
    val region: String = "",
    @SerialName("route_number")    val routeNumber: String = "",
    @SerialName("route_name")      val routeName: String = "",
    @SerialName("driver_name")     val driverName: String = "",
    @SerialName("truck_number")    val truckNumber: String = "",
    @SerialName("helper_name")     val helperName: String = "",
    @SerialName("cases_expected")  val casesExpected: Int = 0,
    val stops: Int = 0,
    @SerialName("start_time")      val startTime: String = "",
    @SerialName("transfer_driver") val transferDriver: String = "",
    @SerialName("transfer_truck")  val transferTruck: String = "",
    val notes: String = "",
    @SerialName("upload_date")     val uploadDate: String = "",
)

@Serializable
data class TractorRow(
    @SerialName("truck_number") val truckNumber: Int = 0,
    @SerialName("trailer_1") val t1: TrailerRow? = null,
    @SerialName("trailer_2") val t2: TrailerRow? = null,
    @SerialName("trailer_3") val t3: TrailerRow? = null,
    @SerialName("trailer_4") val t4: TrailerRow? = null,
)

@Serializable
data class TrailerRow(@SerialName("trailer_number") val trailerNumber: String = "")

// ─── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun LiveViewScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("🚛 Transfer", "📋 All Routes", "🔗 Semis")

    Column(Modifier.fillMaxSize().background(DarkBg)) {
        Row(
            Modifier.fillMaxWidth().background(DarkSurface).padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEachIndexed { i, label ->
                val active = selectedTab == i
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (active) Amber500.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { selectedTab = i }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Normal,
                        color = if (active) Amber500 else MutedText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 1.dp)
        when (selectedTab) {
            0 -> DriverListTab(transferOnly = true)
            1 -> DriverListTab(transferOnly = false)
            2 -> SemisTab()
        }
    }
}

// ─── Driver List Tab ──────────────────────────────────────────────────────────

@Composable
fun DriverListTab(transferOnly: Boolean) {
    val scope = rememberCoroutineScope()
    var routes by remember(transferOnly) { mutableStateOf<List<DriverRoute>>(emptyList()) }
    var loading by remember(transferOnly) { mutableStateOf(true) }
    val transferRegions = setOf("GREENBAY", "WAUSAU", "MKE", "EC")

    LaunchedEffect(transferOnly) {
        loading = true
        scope.launch {
            try {
                val all = BadgerApp.supabase.from("route_drivers").select().decodeList<DriverRoute>()
                routes = (if (transferOnly) all.filter { it.region in transferRegions } else all)
                    .sortedWith(compareBy({ it.transferDriver.ifBlank { "~" } }, { it.region }, { it.routeNumber }))
            } catch (_: Exception) {}
            loading = false
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        return
    }
    if (routes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📄", fontSize = 36.sp)
                Spacer(Modifier.height(8.dp))
                Text("No routing data loaded yet", color = MutedText, fontSize = 13.sp)
            }
        }
        return
    }

    val grouped = routes.groupBy { it.transferDriver.ifBlank { "Local / FDL" } }
        .entries.sortedBy { it.key }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        grouped.forEach { (driverName, driverRoutes) ->
            item { DriverGroupCard(driverName = driverName, routes = driverRoutes) }
        }
    }
}

@Composable
fun DriverGroupCard(driverName: String, routes: List<DriverRoute>) {
    val transferTruck = routes.firstOrNull()?.transferTruck ?: ""
    val totalCases = routes.sumOf { it.casesExpected }
    val regions = routes.map { it.region }.distinct()

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1A))) {
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF222222)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(driverName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    regions.forEach { r ->
                        Text(r, color = Amber500, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.background(Amber500.copy(0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (transferTruck.isNotBlank()) Text("Truck $transferTruck", color = Amber500, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${routes.size} routes • $totalCases cases", color = MutedText, fontSize = 10.sp)
            }
        }
        routes.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.routeNumber, color = Amber500, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.width(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.routeName, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.driverName.isNotBlank())
                        Text(r.driverName + if (r.helperName.isNotBlank()) " + ${r.helperName}" else "",
                            color = MutedText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (r.truckNumber.isNotBlank()) Text(r.truckNumber, color = Green500, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    if (r.casesExpected > 0) Text("${r.casesExpected}cs", color = MutedText, fontSize = 10.sp)
                }
            }
            if (r.notes.isNotBlank())
                Text("📝 ${r.notes}", color = Color(0xFFEAB308).copy(0.7f), fontSize = 10.sp,
                    modifier = Modifier.padding(start = 64.dp, end = 12.dp, bottom = 4.dp))
            HorizontalDivider(color = Color(0xFF222222), thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ─── Semis Tab ────────────────────────────────────────────────────────────────

@Composable
fun SemisTab() {
    val scope = rememberCoroutineScope()
    var slots by remember { mutableStateOf<List<Triple<String, String, Int>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var lastUpdate by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val tractors = BadgerApp.supabase.from("tractors")
                    .select(Columns.raw("truck_number,trailer_1:trailer_list!tractors_trailer_1_id_fkey(trailer_number),trailer_2:trailer_list!tractors_trailer_2_id_fkey(trailer_number),trailer_3:trailer_list!tractors_trailer_3_id_fkey(trailer_number),trailer_4:trailer_list!tractors_trailer_4_id_fkey(trailer_number)")) {
                        filter { eq("is_active", true) }
                    }.decodeList<TractorRow>()

                val printroom = BadgerApp.supabase.from("printroom_entries")
                    .select(Columns.raw("truck_number")).decodeList<kotlinx.serialization.json.JsonObject>()
                    .mapNotNull { it["truck_number"]?.jsonPrimitive?.content?.trim() }.toSet()
                val movement = BadgerApp.supabase.from("live_movement")
                    .select(Columns.raw("truck_number")).decodeList<kotlinx.serialization.json.JsonObject>()
                    .mapNotNull { it["truck_number"]?.jsonPrimitive?.content?.trim() }.toSet()
                val inUse = printroom + movement

                val result = mutableListOf<Triple<String, String, Int>>()
                tractors.forEach { t ->
                    listOf(t.t1 to 1, t.t2 to 2, t.t3 to 3, t.t4 to 4).forEach { (trailer, slot) ->
                        if (trailer == null) return@forEach
                        val key = "${t.truckNumber}-$slot"
                        if (key in inUse) result.add(Triple(key, trailer.trailerNumber, t.truckNumber))
                    }
                }
                result.sortWith(compareBy({ it.third }, { it.first }))
                slots = result
                lastUpdate = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date())
            } catch (_: Exception) {}
            loading = false
        }
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Tonight's Semi Assignments", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                Text("Only tractors in Printroom or Live Movement", color = MutedText, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("● LIVE", color = Green500, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (lastUpdate.isNotBlank()) Text(lastUpdate, color = MutedText, fontSize = 9.sp)
            }
        }

        if (slots.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🚛", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("No semis in Printroom or Live Movement yet", color = MutedText, fontSize = 13.sp)
                }
            }
            return
        }

        val activeTractors = slots.map { it.third }.distinct().size
        val doublePulls = slots.groupBy { it.third }.count { it.value.size >= 2 }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("$activeTractors", "Tractors", Amber500, Modifier.weight(1f))
            StatChip("${slots.size}", "Trailers Out", Color(0xFF8B5CF6), Modifier.weight(1f))
            StatChip("$doublePulls", "Double Pulls", Color(0xFF3B82F6), Modifier.weight(1f))
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(slots) { (slotKey, trailerNum, _) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A1A1A)).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(slotKey, color = Amber500, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text(trailerNum, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StatChip(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.1f))
        .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = MutedText, fontSize = 9.sp)
    }
}
