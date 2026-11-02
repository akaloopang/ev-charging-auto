package com.akaloopang.evauto.voice

import com.akaloopang.evauto.core.model.ChargeType
import com.akaloopang.evauto.core.model.PowerTier
import com.akaloopang.evauto.core.pricing.PriceSource
import com.akaloopang.evauto.core.pricing.ProviderCatalog
import com.akaloopang.evauto.core.pricing.ProviderUnitPrice
import com.akaloopang.evauto.core.pricing.TierPlanner
import com.akaloopang.evauto.core.pricing.TierRange
import com.akaloopang.evauto.core.session.RecordFactory
import com.akaloopang.evauto.core.pricing.StationRef
import com.akaloopang.evauto.core.session.ChargeCostEstimate
import com.akaloopang.evauto.core.stats.MonthSummary
import com.akaloopang.evauto.core.stats.SavingsCalculator
import com.akaloopang.evauto.core.stats.StationPick
import com.akaloopang.evauto.core.stats.StationPicker
import com.akaloopang.evauto.core.voice.BatteryNow
import com.akaloopang.evauto.core.voice.Announcements
import com.akaloopang.evauto.core.voice.PriceTarget
import com.akaloopang.evauto.core.voice.VoiceCommand
import com.akaloopang.evauto.core.voice.VoicePriceParser
import com.akaloopang.evauto.core.voice.VoiceTargets
import com.akaloopang.evauto.data.Repository
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 음성으로 받은 명령을 실제로 적용한다. 화면 음성 입력과 Gleo AI 호출이 같은 함수를 쓴다.
 *
 * 단가는 두 단계: [plan]으로 어느 출력 구간 칸에 넣을지 정하고(필요하면 충전기 출력을 되묻고),
 * 사용자에게 읽어 준 그대로 [apply]로 저장한다.
 */
class VoiceActions(
    private val repo: Repository,
    /** 지금 배터리 — 단가를 정한 뒤 "이번 충전은 ○원, 약 ○킬로미터 갈 수 있어요"를 붙인다 */
    private val battery: () -> BatteryNow? = { null },
) {

    data class Result(
        val ok: Boolean,
        val spoken: String,
        val englishMessage: String,
        val data: Map<String, String> = emptyMap(),
        /** 정보가 더 필요함 (Gleo에 ASKING으로 돌려준다) */
        val needsInput: Boolean = false,
    )

    sealed interface Planned {
        /** 저장 준비 완료 — [question]을 읽어 주고 확인받는다 */
        data class Ready(
            val cmd: VoiceCommand.SetPrice,
            /** 단가를 넣을 구간 칸 (한 칸 — 구간·충전기별 프로모션이 있을 수 있어 이웃 구간에 넣지 않는다) */
            val tiers: List<PowerTier>,
            val base: List<PowerTier>,
            val question: String,
            val recordId: String?,
        ) : Planned
        /** 구간마다 요금이 달라 충전기 출력을 물어야 함 */
        data class NeedKw(
            val cmd: VoiceCommand.SetPrice,
            val candidates: List<PowerTier>,
            val question: String,
            val english: String,
            val recordId: String?,
        ) : Planned
        data class Fail(val spoken: String, val english: String) : Planned
    }

    /**
     * 처음 간 충전소인데 짐작한 단가가 있으면(같은 사업자 다른 충전소·공시가·내 설정) "○○원으로 할까요?"라고 먼저 묻는다.
     * '응' 한마디면 끝나고, 다르면 금액만 말하면 된다.
     */
    data class Suggestion(
        val recordId: String,
        val unitPrice: Double,
        val station: String,
        /** 말로 묻는 출처 ("지난번 ○○점처럼") */
        val source: String,
        /** 화면에 보이는 출처 ("지난번 ○○점(10/12) 단가") */
        val shown: String,
    ) {
        val question: String
            get() = "$station 단가를 $source ${VoicePriceParser.formatPrice(unitPrice)}원으로 할까요? 맞으면 '응', 다르면 금액을 말씀해 주세요."
    }

    fun suggestion(preferredRecordId: String?): Suggestion? {
        val id = stationRecordId(preferredRecordId) ?: return null
        val r = repo.records.value.firstOrNull { it.id == id } ?: return null
        val meta = r.car ?: return null
        if (!meta.askStationPrice || !r.isEstimated || r.unitPrice <= 0) return null
        val ref = meta.priceRef ?: "같은 사업자 충전소"
        val day = meta.priceRefDate?.let { "(${it.monthValue}/${it.dayOfMonth} 확인)" } ?: ""
        val (source, shown) = when (meta.priceSource) {
            PriceSource.OTHER_STATION -> "지난번 ${ref}처럼" to "지난번 $ref$day 단가"
            PriceSource.OFFICIAL_TARIFF -> "${r.provider} 공시 요금" to "${r.provider} 공시 요금"
            PriceSource.MY_SETTING -> "내 설정 단가" to "내 설정 단가"
            else -> return null
        }
        return Suggestion(id, r.unitPrice, stationName(id) ?: "이 충전소", source, shown)
    }

    /** 제안한 단가가 맞다 → 이 충전소 확인 단가로 저장하고 기록도 확인 처리 */
    suspend fun acceptSuggestion(s: Suggestion): Result {
        repo.confirm(s.recordId)
        val p = VoicePriceParser.formatPrice(s.unitPrice)
        return Result(
            true, withAfter("${s.station} 단가를 킬로와트시당 ${p}원으로 저장했어요. 다음부터 자동으로 계산돼요.", s.recordId),
            "Saved $p KRW per kWh for ${s.station}.",
            mapOf("station" to s.station, "unitPrice" to p, "unit" to "KRW/kWh"),
        )
    }

    /** "무료야" — 방금 충전한 충전소를 무료 충전소로 (요금을 받지 않는 곳). 다음부터 0원으로 기록하고 단가를 묻지 않는다 */
    suspend fun markFree(preferredRecordId: String?): Result {
        val id = stationRecordId(preferredRecordId)
        val r = id?.let { i -> repo.records.value.firstOrNull { it.id == i } }
        if (id == null || r == null || repo.station(r.car?.stationKey) == null) {
            return Result(false, "어느 충전소인지 몰라 무료로 표시하지 못했어요.", "Could not tell which station to mark as free.")
        }
        val name = stationName(id) ?: "이 충전소"
        repo.markStationFree(id)
        return Result(
            true, "$name, 무료 충전소로 저장했어요. 다음부터 0원으로 기록하고 단가는 묻지 않아요.",
            "Marked $name as a free charging station. It will be recorded as 0 KRW.",
            mapOf("station" to name, "unitPrice" to "0", "unit" to "KRW/kWh"),
        )
    }

    /** 단가 저장 안내 뒤에 이 충전의 충전량·금액과 지금 갈 수 있는 거리 */
    private fun withAfter(spoken: String, recordId: String?): String {
        val r = repo.records.value.firstOrNull { it.id == recordId }
        val after = runCatching { Announcements.afterPrice(r, battery()) }.getOrNull()
        return if (after != null) "$spoken $after" else spoken
    }

    /** '이 충전소'에 해당하는 기록 (특정 기록에서 시작했으면 그 기록) */
    fun stationRecordId(preferredRecordId: String?): String? =
        preferredRecordId?.takeIf { id -> repo.records.value.any { it.id == id } }
            ?: VoiceTargets.currentStationRecord(repo.records.value, LocalDateTime.now())?.id

    fun stationName(recordId: String?): String? {
        val r = repo.records.value.firstOrNull { it.id == recordId } ?: return null
        return repo.station(r.car?.stationKey)?.displayName ?: r.memo ?: r.provider
    }

    private fun providerName(raw: String): String = repo.price.value.normalizeProvider(raw) ?: raw

    /**
     * 어느 구간 칸에 넣을지 정한다. 속도를 묻는 건 차량 정보·말·공시 요금표로도 갈리지 않을 때뿐이다.
     */
    fun plan(cmd: VoiceCommand.SetPrice, preferredRecordId: String? = null): Planned {
        val member = repo.car.value.useMemberTariff
        return when (val t = cmd.target) {
            PriceTarget.CurrentStation -> {
                val id = stationRecordId(preferredRecordId)
                    ?: return Planned.Fail("최근에 충전한 공용 충전소를 찾지 못했어요.", "No recent public charging session was found to apply the price to.")
                val record = repo.records.value.first { it.id == id }
                val station = repo.station(record.car?.stationKey)
                    ?: return Planned.Fail("충전소 위치를 알 수 없어 저장하지 못했어요.", "The charging station location is unknown, so the price could not be saved.")
                val name = stationName(id) ?: "이 충전소"
                val p = RecordFactory.planTiers(record, station, cmd.unitPrice, repo.pricing(), cmd.speed)
                toPlanned(cmd, p, name, id)
            }
            PriceTarget.Apartment -> Planned.Ready(cmd, emptyList(), emptyList(), VoicePriceParser.confirmQuestion(cmd, null), null)
            is PriceTarget.Provider -> {
                val name = providerName(t.name)
                val c = cmd.copy(target = PriceTarget.Provider(name))
                val p = TierPlanner.plan(cmd.unitPrice, cmd.speed, null, repo.tariff?.find(name), member)
                toPlanned(c, p, name, null)
            }
        }
    }

    private fun toPlanned(cmd: VoiceCommand.SetPrice, p: TierPlanner.Plan, where: String, recordId: String?): Planned = when (p) {
        is TierPlanner.Plan.Fill -> Planned.Ready(cmd, p.tiers, p.tiers, VoicePriceParser.confirmQuestion(cmd, where, p.tiers), recordId)
        is TierPlanner.Plan.AskKw -> Planned.NeedKw(
            cmd, p.candidates, VoicePriceParser.askKwQuestion(where, p.candidates),
            "Prices at $where differ by charger power. Please tell me the rated power in kW written on the charger, for example 50, 100 or 200.",
            recordId,
        )
    }

    /**
     * 출력을 끝내 모를 때. 방금 충전이면 후보 중 가장 낮은 구간(차가 확실히 아는 하한, 공시가로 좁힌 범위 안) 한 칸에만
     * 넣는다 — 어느 구간인지 읽어 주고 확인받는다. 사업자 단가는 어느 칸인지 모르면 넣지 않는다(설정 화면에서 구간별로).
     */
    fun fallback(need: Planned.NeedKw): Planned {
        val cmd = need.cmd
        if (cmd.target == PriceTarget.CurrentStation && need.recordId != null) {
            val tier = need.candidates.minByOrNull { it.ordinal }
            if (tier != null) {
                val name = stationName(need.recordId) ?: "이 충전소"
                return Planned.Ready(cmd, listOf(tier), listOf(tier), VoicePriceParser.confirmQuestion(cmd, name, listOf(tier)), need.recordId)
            }
        }
        return Planned.Fail(
            "충전기 출력을 모르면 어느 구간 단가인지 정할 수 없어요. 설정 화면에서 구간별로 입력해 주세요.",
            "Without the charger power the price tier is unknown. Please enter it per power tier in settings.",
        )
    }

    /** 읽어 주고 확인받은 그대로 저장 */
    suspend fun apply(ready: Planned.Ready): Result {
        val cmd = ready.cmd
        val price = cmd.unitPrice
        val p = VoicePriceParser.formatPrice(price)
        val range = TierRange.label(ready.tiers, spoken = true)
        val rangeShown = TierRange.label(ready.tiers)
        return when (val t = cmd.target) {
            PriceTarget.CurrentStation -> {
                val id = ready.recordId ?: return Result(false, "충전 기록을 찾지 못했어요.", "The charging record was not found.")
                val name = stationName(id) ?: "이 충전소"
                repo.learnStationPrice(id, price, ready.tiers, ready.base, exact = cmd.speed?.exact == true)
                Result(
                    true, withAfter("$name $range 충전기, 킬로와트시당 ${p}원으로 저장했어요. 다음부터 자동으로 계산돼요.", id),
                    "Saved $p KRW per kWh for $name ($rangeShown).",
                    mapOf("station" to name, "power" to rangeShown, "unitPrice" to p, "unit" to "KRW/kWh"),
                )
            }
            PriceTarget.Apartment -> {
                val s = repo.price.value
                val updated = when (cmd.chargeType) {
                    ChargeType.DC -> s.copy(apartmentDcUnitPrice = price)
                    ChargeType.ULTRA -> s.copy(apartmentUltraUnitPrice = price)
                    else -> s.copy(apartmentAcUnitPrice = price)
                }
                // 지난 기록은 그때 단가 그대로 — 다음 충전부터 새 단가 (단가가 비어 있던 기록만 채운다)
                repo.savePrice(updated)
                Result(true, "아파트 충전 단가를 ${p}원으로 저장했어요. 다음 충전부터 적용돼요.", "Saved the apartment charging price as $p KRW per kWh for upcoming sessions.",
                    mapOf("target" to "아파트", "unitPrice" to p))
            }
            is PriceTarget.Provider -> {
                val s = repo.price.value
                val name = providerName(t.name)
                val current = s.providerPrices.firstOrNull { it.provider == name } ?: ProviderUnitPrice(name)
                val next = current.withTiers(ready.tiers, price)
                val providers = if (s.providers.any { ProviderCatalog.norm(it) == ProviderCatalog.norm(name) }) s.providers else s.providers + name
                repo.savePrice(s.copy(providers = providers, providerPrices = s.providerPrices.filterNot { it.provider == name } + next))
                Result(true, "$name $range 충전기 단가를 ${p}원으로 저장했어요. 다음 충전부터 적용돼요.", "Saved $p KRW per kWh for $name ($rangeShown).",
                    mapOf("operator" to name, "power" to rangeShown, "unitPrice" to p))
            }
        }
    }

    /** Gleo처럼 한 번에 처리하는 경로: 정해지면 바로 저장, 출력이 필요하면 되묻기 */
    suspend fun applyNow(cmd: VoiceCommand.SetPrice, preferredRecordId: String? = null): Result = when (val p = plan(cmd, preferredRecordId)) {
        is Planned.Ready -> apply(p)
        is Planned.NeedKw -> Result(false, p.question, p.english, needsInput = true)
        is Planned.Fail -> Result(false, p.spoken, p.english)
    }

    suspend fun settleBill(amount: Long, month: YearMonth?): Result {
        val m = month ?: YearMonth.now().minusMonths(1)
        if (amount <= 0) return Result(false, "고지서 금액을 알아듣지 못했어요.", "The bill amount is missing.")
        val res = repo.settleBill(m, amount, null)
        if (res.updated.isEmpty()) {
            return Result(false, "${m.monthValue}월 아파트 충전 기록이 없어요.", "There are no apartment charging records for $m.")
        }
        return Result(
            true, "${m.monthValue}월 아파트 충전 ${res.updated.size}건에 ${amount}원을 나눠 반영했어요.",
            "Applied the $amount KRW bill to ${res.updated.size} apartment charging records for $m.",
            mapOf("month" to m.toString(), "records" to res.updated.size.toString(), "amount" to amount.toString()),
        )
    }

    fun summary(month: YearMonth?): Result {
        val m = month ?: YearMonth.now()
        val s = MonthSummary.of(repo.records.value, m)
        val total = s.totalAmount.toLong()
        return Result(
            true, "${m.monthValue}월 충전은 ${s.count}건, ${total}원이에요. 아파트 충전은 ${s.apartmentCount}건이에요.",
            "In $m there were ${s.count} charging sessions totaling $total KRW.",
            mapOf("month" to m.toString(), "sessions" to s.count.toString(), "totalKrw" to total.toString(),
                "apartmentSessions" to s.apartmentCount.toString(), "settledRatio" to "%.0f%%".format(s.settledRatio * 100)),
        )
    }

    // ── 주행·내비 관련 (Gleo AI) ─────────────────────────────

    /** 내연기관차 대비 절감액 — 통계 화면과 같은 계산(실주행 거리 ÷ 연비 × 연료 가격 − 실제 충전비) */
    fun savings(month: YearMonth?): Result {
        val p = repo.price.value
        val all = repo.records.value
        val rs = if (month == null) all else all.filter { YearMonth.from(it.date) == month }
        val s = SavingsCalculator.compute(rs, p.effectiveGasEfficiency, p.effectiveGasPrice)
        val scope = month?.let { "${it.monthValue}월" } ?: "지금까지"
        if (!s.available) {
            return Result(
                false, "$scope 절감액은 아직 계산할 수 없어요. 계기판 주행거리가 있는 충전 기록이 두 번 이상 필요해요.",
                "Savings cannot be calculated yet. At least two charging records with odometer readings are needed.",
            )
        }
        val saved = s.savings.toLong()
        val km = s.distanceKm.toLong()
        val spoken = if (saved >= 0) "$scope ${km}킬로미터를 달리며 내연기관차보다 ${Announcements.won(s.savings)} 아꼈어요."
        else "$scope ${km}킬로미터를 달리며 내연기관차보다 ${Announcements.won(-s.savings)} 더 썼어요."
        return Result(
            true, spoken,
            "Over $km km you saved $saved KRW compared with a gasoline car.",
            mapOf("distanceKm" to km.toString(), "savingsKrw" to saved.toString(),
                "evCostKrw" to s.evCost.toLong().toString(), "gasCostKrw" to s.gasCost.toLong().toString()),
        )
    }

    /** 지금 내비로 가는 충전소의 예상 충전비 */
    fun expectedCost(e: ChargeCostEstimate?): Result {
        if (e == null) {
            return Result(
                false, "지금 내비로 가는 충전소가 없어요. 충전소를 목적지로 정하면 예상 충전비를 알려 드려요.",
                "No charging station is set as the navigation destination.",
            )
        }
        return Result(
            true, Announcements.chargePlan(e, battery()),
            e.amount?.let { "Charging from ${e.fromSoc.toInt()}% to ${e.toSoc.toInt()}% at ${e.stationName} will cost about ${it.toLong()} KRW." }
                ?: "The unit price of ${e.stationName} is not known yet.",
            buildMap {
                put("station", e.stationName)
                put("fromSoc", e.fromSoc.toInt().toString())
                put("toSoc", e.toSoc.toInt().toString())
                e.kWh?.let { put("kWh", "%.1f".format(it)) }
                e.amount?.let { put("expectedKrw", it.toLong().toString()) }
                e.unitPrice?.let { put("unitPrice", it.toLong().toString()) }
            },
        )
    }

    /**
     * 내 기록 속 충전소로 길안내. [route]가 내비에 목적지를 넘긴다(연결 안 됐으면 false).
     */
    fun navigate(pick: StationPick, name: String?, route: (StationRef) -> Boolean): Result {
        val found = StationPicker.pick(repo.records.value, repo.stations.value, pick, name)
            ?: return Result(
                false,
                if (pick == StationPick.NAMED) "'${name.orEmpty()}' 충전소를 기록에서 찾지 못했어요." else "길안내할 충전소가 기록에 없어요. 위치가 남은 충전 기록이 필요해요.",
                "No matching charging station with a location was found in the records.",
                needsInput = pick == StationPick.NAMED,
            )
        val st = found.station
        if (!route(st)) {
            return Result(false, "내비게이션에 연결하지 못했어요.", "Could not connect to the navigation.")
        }
        val price = found.avgUnitPrice?.let { " 평균 단가는 ${it.toLong()}원이었어요." } ?: ""
        return Result(
            true, "${st.displayName}까지 길안내를 시작할게요.$price",
            "Starting navigation to ${st.displayName}.",
            buildMap {
                put("station", st.displayName)
                put("visits", found.visits.toString())
                found.avgUnitPrice?.let { put("avgUnitPrice", it.toLong().toString()) }
            },
        )
    }
}
