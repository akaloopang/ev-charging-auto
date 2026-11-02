package com.akaloopang.evauto.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.akaloopang.evauto.EvAutoApp
import com.akaloopang.evauto.core.model.ChargingRecord
import com.akaloopang.evauto.core.pricing.StationRef
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** 충전 기록: 달별 묶음, 한 줄에 날짜·충전소·충전량·금액. 누르면 상세(영수증 입력·충전소 이름·삭제) */
@Composable
fun RecordsScreen(app: EvAutoApp) {
    val records by app.repo.records.collectAsStateWithLifecycle()
    val stations by app.repo.stations.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ChargingRecord?>(null) }
    val byMonth = records.groupBy { YearMonth.from(it.date) }.toSortedMap(compareByDescending { it })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PagePadding) {
        item {
            PageHeader(
                "충전 기록",
                subtitle = if (records.isEmpty()) null else "${records.size}건 · 누르면 상세 내용과 영수증 입력",
            )
        }
        if (records.isEmpty()) {
            item {
                Column(Modifier.padding(top = 32.dp)) {
                    EmptyState(
                        "아직 충전 기록이 없어요",
                        "충전기를 연결하면 자동으로 기록돼요. 앱이 꺼져 있었던 밤사이 아파트 충전도 다음에 켤 때 찾아서 기록해요.",
                    )
                }
            }
        }
        byMonth.forEach { (month, list) ->
            item(key = "h$month") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        "${month.year}년 ${month.monthValue}월",
                        fontSize = TextSize.section, fontWeight = FontWeight.Bold, color = Pleos.colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${list.size}건 · ${Format.won(list.sumOf { it.paymentAmount })}",
                        fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = Pleos.colors.text,
                    )
                }
                SectionDivider()
            }
            items(list, key = { it.id }) { r ->
                RecordRow(r, stations.firstOrNull { it.key == r.car?.stationKey }) { selected = r }
            }
        }
    }

    selected?.let { r -> RecordDialog(app, r) { selected = null } }
}

@Composable
internal fun RecordRow(r: ChargingRecord, station: StationRef?, onClick: () -> Unit) {
    val c = Pleos.colors
    val meta = r.car
    Row(
        Modifier.fillMaxWidth().heightIn(min = 120.dp).clickable(onClick = onClick).padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // 날짜 칸: 큰 날 + 요일·시각
        Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${r.date.dayOfMonth}", fontSize = TextSize.headline, fontWeight = FontWeight.Bold, color = c.text)
            Text(
                "${r.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)} ${"%02d:%02d".format(r.date.hour, r.date.minute)}",
                fontSize = TextSize.caption, color = c.sub,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Format.title(r), fontSize = TextSize.title, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(
                station?.displayName,
                meta?.socStart?.let { "SOC $it→${meta?.socEnd ?: "-"}%" },
                Format.kwh(r.kWh),
            )
            Text(details.joinToString(" · "), fontSize = TextSize.label, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    r.isFree -> "0원"
                    r.paymentAmount > 0 -> Format.won(r.paymentAmount)
                    else -> "단가 없음"
                },
                fontSize = TextSize.section, fontWeight = FontWeight.Bold,
                color = if (r.paymentAmount > 0 || r.isFree) c.text else c.sub,
            )
            StatusChip(r.amountStatus, r.isFree)
        }
    }
    SectionDivider()
}
