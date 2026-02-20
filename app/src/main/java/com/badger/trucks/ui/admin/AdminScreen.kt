package com.badger.trucks.ui.admin

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badger.trucks.data.*
import com.badger.trucks.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun AdminScreen() {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Trucks", "Tractors", "Statuses", "Fleet")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // Tab row
        ScrollableTabRow(
            selectedTabIndex = tab,
            containerColor = DarkSurface,
            contentColor = Amber500,
            edgePadding = 8.dp,
            divider = { Divider(color = DarkBorder) }
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = {
                        Text(
                            title,
                            fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (tab == index) Amber500 else MutedText
                        )
                    }
                )
            }
        }

        when (tab) {
            0 -> TruckSection()
            1 -> TractorSection()
            2 -> StatusSection()
            3 -> FleetSection()
        }
    }
}

@Composable
fun TruckSection() {
    val scope = rememberCoroutineScope()
    var trucks by remember { mutableStateOf<List<Truck>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try { trucks = BadgerRepo.getTrucks() } catch (e: Exception) { e.printStackTrace() }
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text("🚚 Truck Database", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LightText)
            Text("${trucks.size} trucks", color = MutedText, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        items(trucks) { truck ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        truck.truckNumber.toString(),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp,
                        color = Amber500
                    )
                    Column {
                        Text(truck.truckType.replace("_", " ").replaceFirstChar { it.uppercase() },
                            color = LightText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(truck.transmission.replaceFirstChar { it.uppercase() },
                            color = MutedText, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (truck.notes != null) {
                        Text(truck.notes, color = MutedText, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun TractorSection() {
    val scope = rememberCoroutineScope()
    var tractors by remember { mutableStateOf<List<Tractor>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try { tractors = BadgerRepo.getTractors() } catch (e: Exception) { e.printStackTrace() }
        loading = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Amber500)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("🚛 Tractor Trailers", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LightText)
            Text("${tractors.size} tractors", color = MutedText, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        items(tractors) { t ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.truckNumber.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Amber500)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(t.driverName ?: "—", fontWeight = FontWeight.Bold, color = LightText, fontSize = 14.sp)
                            if (t.driverCell != null) Text(t.driverCell, color = MutedText, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrailerChip("${t.truckNumber}-1", t.trailer1?.trailerNumber, Green400)
                        TrailerChip("${t.truckNumber}-2", t.trailer2?.trailerNumber, Blue400)
                        TrailerChip("${t.truckNumber}-3", t.trailer3?.trailerNumber, Purple400)
                        TrailerChip("${t.truckNumber}-4", t.trailer4?.trailerNumber, Pink400)
                    }
                }
            }
        }
    }
}

@Composable
fun TrailerChip(label: String, trailerNum: String?, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = DarkCard,
        modifier = Modifier
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("$label:", color = MutedText, fontSize = 10.sp)
            Spacer(Modifier.width(3.dp))
            Text(trailerNum ?: "—", color = if (trailerNum != null) color else MutedText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

@Composable
fun StatusSection() {
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<StatusValue>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    fun load() { scope.launch {
        try { statuses = BadgerRepo.getStatuses() } catch (e: Exception) { e.printStackTrace() }
        loading = false
    }}

    LaunchedEffect(Unit) { load() }

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Amber500) }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("🏷️ Status Values", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LightText)
            Spacer(Modifier.height(12.dp))
        }
        item {
            // Wrapped grid of status badges
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                statuses.forEach { s ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = try { Color(android.graphics.Color.parseColor(s.statusColor)) } catch (_: Exception) { MutedText }
                    ) {
                        Text(
                            s.statusName,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FleetSection() {
    val scope = rememberCoroutineScope()
    var trucks by remember { mutableStateOf<List<Truck>>(emptyList()) }
    var tractors by remember { mutableStateOf<List<Tractor>>(emptyList()) }
    var movement by remember { mutableStateOf<List<LiveMovement>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            trucks = BadgerRepo.getTrucks()
            tractors = BadgerRepo.getTractors()
            movement = BadgerRepo.getLiveMovement()
        } catch (e: Exception) { e.printStackTrace() }
        loading = false
    }

    val activeTrucks = movement.map { it.truckNumber }.toSet()

    if (loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Amber500) }
        return
    }

    val inUse = trucks.count { activeTrucks.contains(it.truckNumber.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text("🚛 Fleet Inventory", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = LightText)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatBox("Total", trucks.size.toString())
                StatBox("In Use", inUse.toString(), Green400)
                StatBox("Available", (trucks.size - inUse).toString(), MutedText)
            }
            Spacer(Modifier.height(8.dp))
        }
        items(trucks) { truck ->
            val isInUse = activeTrucks.contains(truck.truckNumber.toString())
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isInUse) Green500.copy(alpha = 0.08f) else DarkSurface
                ),
                shape = RoundedCornerShape(10.dp),
                border = if (isInUse) CardDefaults.outlinedCardBorder().copy() else null
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(truck.truckNumber.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = Amber500)
                    Spacer(Modifier.width(12.dp))
                    Text(truck.truckType.replace("_", " ").replaceFirstChar { it.uppercase() }, color = MutedText, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (isInUse) {
                        Surface(shape = RoundedCornerShape(4.dp), color = Green500) {
                            Text("IN USE", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatBox(label: String, value: String, color: Color = LightText) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = color)
        Text(label, color = MutedText, fontSize = 10.sp)
    }
}

@Composable
fun FlowRow(
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit
) {
    // Simple flow layout using built-in
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement
    ) {
        content()
    }
}
