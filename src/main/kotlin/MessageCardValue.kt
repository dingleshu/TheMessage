package com.fengsheng

import com.fengsheng.RobotPlayer.Companion.sortCards
import com.fengsheng.ScoreFactory.logger
import com.fengsheng.card.Card
import com.fengsheng.card.count
import com.fengsheng.card.countTrueCard
import com.fengsheng.card.filter
import com.fengsheng.phase.FightPhaseIdle
import com.fengsheng.protos.Common.*
import com.fengsheng.protos.Common.card_type.*
import com.fengsheng.protos.Common.color.*
import com.fengsheng.protos.Common.direction.*
import com.fengsheng.protos.Common.secret_task.*
import com.fengsheng.skill.*
import com.fengsheng.skill.LengXueXunLian.MustLockOne
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 判断是否有玩家会死或者赢
 */
fun Iterable<Player?>.anyoneWillWinOrDie(e: FightPhaseIdle) = any {
    it!!.willWin(e.whoseTurn, e.inFrontOfWhom, e.messageCard)
} || e.inFrontOfWhom.willDie(e.messageCard)

/**
 * 判断玩家是否会死
 */
fun Player.willDie(card: Card) = messageCards.count(Black) >= 2 && card.isBlack()

/**
 * 判断玩家是否会死
 */
fun Player.willDie(colors: List<color>) = messageCards.count(Black) >= 2 && Black in colors

/**
 * 判断玩家是否能赢
 *
 * TODO: 这里没有判断传出者
 *
 * @param whoseTurn 当前回合玩家
 * @param inFrontOfWhom 情报在谁面前
 * @param card 情报牌
 */
fun Player.willWin(whoseTurn: Player, inFrontOfWhom: Player, card: Card) =
    calculateMessageCardValue(whoseTurn, inFrontOfWhom, card) >= 600

/**
 * 判断玩家是否能赢
 *
 * TODO: 这里没有判断传出者
 *
 * @param whoseTurn 当前回合玩家
 * @param inFrontOfWhom 情报在谁面前
 * @param colors 情报牌的颜色
 */
fun Player.willWin(whoseTurn: Player, inFrontOfWhom: Player, colors: List<color>) =
    calculateMessageCardValue(whoseTurn, inFrontOfWhom, colors) >= 600

// TODO CP胜利条件太复杂了，还会引发一大堆bug，先不写了
// /**
// * 判断玩家是否赢了，考虑CP小九和CP韩梅的技能，不考虑簒夺者
// */
// private fun Player.willWinInternalCp(
//    whoseTurn: Player,
//    inFrontOfWhom: Player,
//    colors: List<color>,
//    partnerTogether: Boolean = true
// ): Boolean {
//    if (!alive) return false
//    if (willWinInternal(whoseTurn, inFrontOfWhom, colors, partnerTogether)) return true
//    val g = whoseTurn.game!!
//    fun isCpXiaoJiu(p: Player?) = p!!.alive && p.skills.any { it is YiZhongRen }
//    fun isCpHanMei(p: Player?) = p!!.alive && p.skills.any { it is BaiYueGuang }
//    fun isHanMei(p: Player) = p.alive && p.roleName.endsWith("韩梅")
//    fun isXiaoJiu(p: Player) = p.alive && p.roleName.endsWith("小九")
//    val counts = CountColors(inFrontOfWhom.messageCards)
//    counts += colors
//    if (isCpXiaoJiu(this) && isHanMei(inFrontOfWhom) && counts.red >= 3 &&
//        !inFrontOfWhom.willWinInternal(whoseTurn, inFrontOfWhom, colors, false)
//    ) return true
//    if (isCpHanMei(this) && isXiaoJiu(inFrontOfWhom) && counts.blue >= 3 &&
//        !inFrontOfWhom.willWinInternal(whoseTurn, inFrontOfWhom, colors, false)
//    ) return true
//    if (this === inFrontOfWhom) {
//        if (isHanMei(this) && counts.red >= 3 && g.players.any(::isCpXiaoJiu)) return true
//        if (isXiaoJiu(this) && counts.blue >= 3 && g.players.any(::isCpHanMei)) return true
//    }
//    return false
// }

/**
 * 判断玩家是否赢了，不考虑簒夺者
 *
 * @param checkAllSecretTask 是否判断所有神秘人的任务
 */
private fun Player.willWinInternal(
    whoseTurn: Player,
    inFrontOfWhom: Player,
    colors: List<color>,
    partnerTogether: Boolean = true,
    checkAllSecretTask: Boolean = false,
): Boolean {
    if (!alive) return false
    if (identity != Black) {
        return (if (partnerTogether) isPartnerOrSelf(inFrontOfWhom) else this === inFrontOfWhom) &&
            identity in colors && inFrontOfWhom.messageCards.count(identity) >= 2
    } else {
        fun checkSecretTask(secretTask: secret_task): Boolean {
            return when (secretTask) {
                Killer, Pioneer, Sweeper -> {
                    if (game!!.players.any {
                            (it!!.identity != Black || it.secretTask in listOf(Collector, Mutator)) &&
                                it.willWinInternal(whoseTurn, inFrontOfWhom, colors)
                        }) {
                        return false
                    }
                    val counts = CountColors(inFrontOfWhom.messageCards)
                    counts += colors
                    counts.black >= 3 || return false
                    when (secretTask) {
                        Killer -> this === whoseTurn && counts.trueCard >= 2
                        Pioneer -> this === inFrontOfWhom && counts.trueCard >= 1
                        Sweeper -> counts.red <= 1 && counts.blue <= 1
                        else -> false
                    }
                }

                Collector ->
                    this === inFrontOfWhom &&
                        if (Red in colors) messageCards.count(Red) >= 2
                        else if (Blue in colors) messageCards.count(Blue) >= 2
                        else false

                Mutator -> {
                    if (inFrontOfWhom.let {
                            (it.identity != Black || it.secretTask == Collector) &&
                                it.willWinInternal(whoseTurn, it, colors)
                        }) {
                        return false
                    }
                    if (Red in colors && inFrontOfWhom.messageCards.count(Red) >= 2) return true
                    if (Blue in colors && inFrontOfWhom.messageCards.count(Blue) >= 2) return true
                    false
                }

                Disturber ->
                    if (Red in colors || Blue in colors)
                        game!!.players.all {
                            it === this || it === inFrontOfWhom || !it!!.alive ||
                                it.messageCards.countTrueCard() >= 2
                        } &&
                            inFrontOfWhom.messageCards.countTrueCard() >= 1
                    else
                        game!!.players.all { it === this || !it!!.alive || it.messageCards.countTrueCard() >= 2 }

                else -> false
            }
        }
        if (checkAllSecretTask && game!!.possibleSecretTasks.isNotEmpty())
            return game!!.possibleSecretTasks.any { checkSecretTask(it) }
        return checkSecretTask(secretTask)
    }
}

/**
 * 计算随机颜色情报牌的平均价值
 *
 * @param whoseTurn 当前回合玩家
 * @param inFrontOfWhom 情报在谁面前
 * @param checkThreeSame 是否禁止三张同色。为`true`时，三张同色导致加入手牌认为是10分。为`false`时，三张同色就赢了/死了。默认为`false`。
 */
fun Player.calculateMessageCardValue(whoseTurn: Player, inFrontOfWhom: Player, checkThreeSame: Boolean = false) =
    game!!.deck.colorRates.withIndex().sumOf { (i, rate) ->
        val colors =
            if (i % 3 == i / 3) listOf(color.forNumber(i / 3))
            else listOf(color.forNumber(i / 3), color.forNumber(i % 3))
        calculateMessageCardValue(whoseTurn, inFrontOfWhom, colors, checkThreeSame) * rate
    }

/**
 * 计算情报牌的价值
 *
 * @param whoseTurn 当前回合玩家
 * @param inFrontOfWhom 情报在谁面前
 * @param card 情报牌
 * @param checkThreeSame 是否禁止三张同色。为`true`时，三张同色导致加入手牌认为是10分。为`false`时，三张同色就赢了/死了。默认为`false`。
 * @param sender 情报传出者，null表示这并不是在计算待收情报
 */
fun Player.calculateMessageCardValue(
    whoseTurn: Player,
    inFrontOfWhom: Player,
    card: Card,
    checkThreeSame: Boolean = false,
    sender: Player? = null
) = calculateMessageCardValue(whoseTurn, inFrontOfWhom, card.colors, checkThreeSame, sender)

/**
 * 计算移除一张情报牌的价值
 */
fun Player.calculateRemoveCardValue(whoseTurn: Player, from: Player, card: Card): Int {
    val index = from.messageCards.indexOfFirst { it.id == card.id }
    from.messageCards.removeAt(index)
    return -calculateMessageCardValue(whoseTurn, from, card).apply {
        from.messageCards.add(index, card)
    }
}

/**
 * 计算情报牌的价值
 *
 * @param whoseTurn 当前回合玩家
 * @param inFrontOfWhom 情报在谁面前
 * @param colors 情报牌的颜色
 * @param checkThreeSame 是否禁止三张同色。为`true`时，三张同色导致加入手牌认为是10分。为`false`时，三张同色就赢了/死了。默认为`false`。
 * @param sender 情报传出者，null表示这并不是在计算待收情报
 */
fun Player.calculateMessageCardValue(
    whoseTurn: Player,
    inFrontOfWhom: Player,
    colors: List<color>,
    checkThreeSame: Boolean = false,
    sender: Player? = null
): Int {
    var v1 = calculateMessageCardValue(whoseTurn, inFrontOfWhom, colors, checkThreeSame)
    if (sender != null) {
        // TODO 临时这样写，后续应该改成调用Player.countMessageCard来计数
        class TmpCard(colors: List<color>) : Card(999, colors, Up, false) {
            override val type: card_type = card_type.UNRECOGNIZED
            override fun canUse(g: Game, r: Player, vararg args: Any) = false
            override fun execute(g: Game, r: Player, vararg args: Any) = Unit
        }
        fun merge(a: Int, b: Int): Int = if (a == 600 || b == 600) 600 // 其中一个已经赢了，就是赢了，以防共赢的情况下还非要出牌拦敌方
        else a + b
        if (colors.size == 2 && inFrontOfWhom.skills.any { it is JinShen }) { // 金生火
            var valueInFrontOfWhom = 0
            for (c in inFrontOfWhom.cards.toList()) {
                val v = inFrontOfWhom.calculateMessageCardValue(whoseTurn, inFrontOfWhom, c.colors, checkThreeSame)
                if (v > valueInFrontOfWhom) {
                    valueInFrontOfWhom = v
                    v1 = calculateMessageCardValue(whoseTurn, inFrontOfWhom, c.colors, checkThreeSame)
                }
            }
        }
        if (sender.skills.any { it is MianLiCangZhen }) { // 邵秀
            inFrontOfWhom.messageCards.add(TmpCard(colors))
            var valueSender = -1
            var valueMe = 0
            for (c in sender.cards.filter(Card::isBlack)) {
                val v = sender.calculateMessageCardValue(whoseTurn, inFrontOfWhom, c.colors, checkThreeSame)
                if (v > valueSender) {
                    valueSender = v
                    valueMe = calculateMessageCardValue(whoseTurn, inFrontOfWhom, c.colors, checkThreeSame)
                }
            }
            logger.debug("这是[邵秀]传出的情报，计算[绵里藏针]额外分数为$valueMe")
            v1 = merge(v1, valueMe)
            inFrontOfWhom.messageCards.removeLast()
        }
        if (Black in colors && inFrontOfWhom.skills.any { it is YiYaHuanYa }) { // 王魁
            inFrontOfWhom.messageCards.add(TmpCard(colors))
            var valueInFrontOfWhom = -1
            var valueMe = 0
            for (c in inFrontOfWhom.cards.filter(Card::isBlack)) {
                for (p in listOf(sender, sender.getNextLeftAlivePlayer(), sender.getNextRightAlivePlayer())) {
                    val v = inFrontOfWhom.calculateMessageCardValue(whoseTurn, p, c.colors, checkThreeSame)
                    if (v > valueInFrontOfWhom) {
                        valueInFrontOfWhom = v
                        valueMe = calculateMessageCardValue(whoseTurn, p, c.colors, checkThreeSame)
                    }
                }
            }
            v1 = merge(v1, valueMe)
            logger.debug("这是[王魁]传出的情报，计算[以牙还牙]额外分数为$valueMe")
            inFrontOfWhom.messageCards.removeLast()
        }
        if (Black !in colors && sender.skills.any { it is ChiZiZhiXin } && sender !== inFrontOfWhom) { // 青年小九
            inFrontOfWhom.messageCards.add(TmpCard(colors))
            var valueSender = 30
            var valueMe = 0
            for (c in sender.cards.filter { it.colors.any { c -> c in colors } }) {
                val v = sender.calculateMessageCardValue(whoseTurn, sender, c.colors, checkThreeSame)
                if (v > valueSender) {
                    valueSender = v
                    valueMe = calculateMessageCardValue(whoseTurn, sender, c.colors, checkThreeSame)
                }
            }
            logger.debug("这是[SP小九]传出的情报，计算[赤子之心]额外分数为$valueMe")
            v1 = merge(v1, when {
                valueSender > 30 -> valueMe
                isPartnerOrSelf(sender) -> 20
                else -> -20
            })
            inFrontOfWhom.messageCards.removeLast()
        }
        // TODO CP韩梅还要判断一下拿牌，这里就暂时先不写了
        if (Blue in colors && sender.skills.any { it is AnCangShaJi }) { // SP韩梅
            inFrontOfWhom.messageCards.add(TmpCard(colors))
            if (sender.cards.any { it.isPureBlack() }) {
                val v = sender.calculateMessageCardValue(whoseTurn, inFrontOfWhom, listOf(Black))
                var valueMe = 0
                if (v > 0) valueMe = calculateMessageCardValue(whoseTurn, inFrontOfWhom, listOf(Black))
                logger.debug("这是[CP韩梅]传出的情报，计算[暗藏杀机]额外分数为$valueMe")
                v1 = merge(v1, valueMe)
            }
            inFrontOfWhom.messageCards.removeLast()
        }
        fun addScore(p: Player, score: Int) {
            if (p.identity != Black) { // 军潜：己方加分，敌方减分，神秘人不管
                if (identity == p.identity) v1 = merge(v1, score)
                if (identity != p.identity && identity != Black) v1 = merge(v1, -score)
            } else if (p === this) { // 神秘人：自己加分，其他人不管
                v1 = merge(v1, score)
            }
        }
        if (Black in colors && inFrontOfWhom.skills.any { it is ShiSi }) { // 老汉【视死】
            addScore(inFrontOfWhom, 20)
        }
        if (inFrontOfWhom.skills.any { it is ZhiYin }) { // 程小蝶【知音】【惊梦】
            if (Black in colors) { // 惊梦
                addScore(inFrontOfWhom, 10)
            }
            if (colors.any { it != Black }) { // 知音
                addScore(inFrontOfWhom, 10)
                addScore(sender, 10)
            }
        }
        if (sender.skills.any { it is MingEr }) { // 老鳖【明饵】
            if (colors.any { it != Black }) {
                addScore(sender, 10)
                addScore(inFrontOfWhom, 10)
            }
        }
        if (sender !== inFrontOfWhom && sender.roleFaceUp && sender.skills.any { it is ZhenLi }) {
            // 李书云【真理】
            if (colors.any { it != Black }) {
                addScore(sender, 20)
            }
        }
        if (sender !== inFrontOfWhom && sender.skills.any { it is HanHouLaoShi }) {
            // 哑炮【憨厚老实】
            if (sender.cards.isNotEmpty()) {
                addScore(sender, -10)
                addScore(inFrontOfWhom, 10)
            }
        }
        if (colors.any { it != Black } && inFrontOfWhom.skills.any { it is WorkersAreKnowledgable }) {
            // 王响【咱们工人有知识】
            addScore(inFrontOfWhom, 9)
        }
        if (Black !in colors && inFrontOfWhom.skills.any { it is ZhuanJiao || it is JiSong }) {
            // 白小年【转交】、鬼脚【急送】
            addScore(inFrontOfWhom, 11)
        }
        if (sender.skills.any { it is CangShenJiaoTang }) {
            // 玛利亚【藏身教堂】
            if (sender.isPartnerOrSelf(inFrontOfWhom) && !inFrontOfWhom.isPublicRole && inFrontOfWhom.roleFaceUp) {
                addScore(inFrontOfWhom, 80)
            }
        }
        if (!inFrontOfWhom.roleFaceUp && (inFrontOfWhom.hasEverFaceUp || inFrontOfWhom === this) &&
            inFrontOfWhom !== sender && inFrontOfWhom.skills.any { it is LianXin }) {
            // 成年小九、成年韩梅【暗度陈仓】
            addScore(inFrontOfWhom, if (sender.isPartner(inFrontOfWhom)) 20 else 10)
        }
        if (Black in colors && inFrontOfWhom.roleFaceUp &&
            inFrontOfWhom.skills.any { it is YiXin } && inFrontOfWhom.messageCards.count(Black) == 2) {
            // 李宁玉【遗信】
            var liNingYuValue = -1
            var myValue = 0
            for (handCard in inFrontOfWhom.cards) {
                for (p in game!!.players) {
                    if (!p!!.alive || p === inFrontOfWhom) continue
                    val v = inFrontOfWhom.calculateMessageCardValue(whoseTurn, p, handCard)
                    if (v > liNingYuValue) {
                        liNingYuValue = v
                        myValue = calculateMessageCardValue(whoseTurn, p, handCard)
                    }
                }
            }
            v1 = merge(v1, myValue)
        }
        if (Black in colors && inFrontOfWhom.skills.any { it is RuGui } && inFrontOfWhom.messageCards.count(Black) == 2) {
            // 老汉【如归】
            if (whoseTurn !== inFrontOfWhom && whoseTurn.alive) {
                var laoHanValue = -1
                var myValue = 0
                for (mCard in inFrontOfWhom.messageCards + TmpCard(colors)) {
                    val v = inFrontOfWhom.calculateMessageCardValue(whoseTurn, whoseTurn, mCard)
                    if (v > laoHanValue) {
                        laoHanValue = v
                        myValue = calculateMessageCardValue(whoseTurn, whoseTurn, mCard)
                    }
                }
                v1 = merge(v1, myValue)
            }
        }
    }
    if (v1 > 460) v1 = 600
    else if (v1 < -460) v1 = -600
    v1 = (v1 * coefficientA + coefficientB).roundToInt()
    if (v1 > 460) v1 = 600
    else if (v1 < -460) v1 = -600
    return v1
}

/**
 * 计算情报牌的价值
 *
 * @param whoseTurn 当前回合玩家
 * @param inFrontOfWhom 情报在谁面前
 * @param colors 情报牌的颜色
 * @param checkThreeSame 是否禁止三张同色。为`true`时，三张同色导致加入手牌认为是10分。为`false`时，三张同色就赢了/死了。默认为`false`。
 */
fun Player.calculateMessageCardValue(
    whoseTurn: Player,
    inFrontOfWhom: Player,
    colors: List<color>,
    checkThreeSame: Boolean = false
): Int {
    val disturber = game!!.players.find { it!!.alive && it.identity == Black && it.secretTask == Disturber }
    if (!checkThreeSame) {
        if (whoseTurn.identity == Black && whoseTurn.secretTask == Stealer) {
            if (this === whoseTurn) { // 簒夺者的回合，任何人赢了，簒夺者都会赢
                if (game!!.players.any { it !== disturber && it!!.willWinInternal(whoseTurn, inFrontOfWhom, colors) })
                    return 600
            } else { // 簒夺者的回合，任何人赢了，都算作输
                if (game!!.players.any { it !== disturber && it!!.willWinInternal(whoseTurn, inFrontOfWhom, colors) })
                    return -600
            }
        } else if (whoseTurn.roleFaceUp && whoseTurn.skills.any { it is BiYiShuangFei }) {
            if (this === whoseTurn) { // 秦圆圆的回合，任何男性角色赢了，秦圆圆都会赢
                if (game!!.players.any {
                        it !== disturber && (isPartnerOrSelf(it!!) || it.isMale) &&
                            it.willWinInternal(whoseTurn, inFrontOfWhom, colors, false)
                    }) return 600
                if (game!!.players.any {
                        it !== disturber && !(isPartnerOrSelf(it!!) || it.isMale) &&
                            it.willWinInternal(whoseTurn, inFrontOfWhom, colors, false)
                    }) return -600
            } else if (identity == Black) { // 秦圆圆的回合，神秘人没关系，反正没有队友
                if (game!!.players.any {
                        it !== disturber && !isEnemy(it!!) && it.willWinInternal(whoseTurn, inFrontOfWhom, colors)
                    }) return 600
                val coefficient = if (coefficientA >= 1) coefficientA - 0.2 else coefficientA
                if (game!!.players.any {
                        it !== disturber && isEnemy(it!!) && it.willWinInternal(whoseTurn, inFrontOfWhom, colors,
                            checkAllSecretTask = coefficientA >= 1) // 激进型需要判断所有神秘人任务
                    }) return if (Random.nextDouble() < coefficient) -600 else 0 // 根据打牌风格，有80%到100%几率管
            } else if (inFrontOfWhom.identity in colors && inFrontOfWhom.messageCards.count(inFrontOfWhom.identity) >= 2) {
                return if (inFrontOfWhom === this || isPartner(inFrontOfWhom) &&
                    (!inFrontOfWhom.isMale || isPartnerOrSelf(whoseTurn))
                ) 600 else -600
            }
        } else {
            if (game!!.players.any {
                    it !== disturber && !isEnemy(it!!) && it.willWinInternal(whoseTurn, inFrontOfWhom, colors)
                }) return 600
            if (game!!.players.any {
                    it !== disturber && isEnemy(it!!) && it.willWinInternal(whoseTurn, inFrontOfWhom, colors)
                }) return -600
        }
    }
    if (disturber != null && disturber.willWinInternal(whoseTurn, inFrontOfWhom, colors))
        return if (disturber === this) 300 else -300
    var value = 0
    if (identity == Black) {
        if (secretTask == Collector && this === inFrontOfWhom) {
            if (Red in colors) {
                value += when (messageCards.count(Red)) {
                    0 -> 10
                    1 -> 100
                    else -> if (checkThreeSame) return 10 else 1000
                }
            }
            if (Blue in colors) {
                value += when (messageCards.count(Blue)) {
                    0 -> 10
                    1 -> 100
                    else -> if (checkThreeSame) return 10 else 1000
                }
            }
        }
        if (secretTask == Mutator && this === inFrontOfWhom) {
            if (Red in colors) {
                value += when (messageCards.count(Red)) {
                    0, 1 -> 6
                    else -> if (checkThreeSame) return 10 else 1000
                }
            }
            if (Blue in colors) {
                value += when (messageCards.count(Blue)) {
                    0, 1 -> 6
                    else -> if (checkThreeSame) return 10 else 1000
                }
            }
        }
        if (secretTask == Disturber && this != inFrontOfWhom) {
            val count = inFrontOfWhom.messageCards.countTrueCard()
            if (inFrontOfWhom.willDie(colors))
                value += ((2 - count) * 5).coerceAtLeast(0)
            else if (count < 2 && (Red in colors || Blue in colors))
                value += 5
        }
        if (secretTask !in listOf(Killer, Pioneer, Sweeper)) {
            if (this === inFrontOfWhom && Black in colors) {
                value -= when (inFrontOfWhom.messageCards.count(Black)) {
                    0 -> 1
                    1 -> 11
                    else -> if (checkThreeSame) return 10 else 112
                }
            }
        } else {
            if (Black in colors) {
                val useless = secretTask == Sweeper && inFrontOfWhom.messageCards.countTrueCard() > 1
                value += when (inFrontOfWhom.messageCards.count(Black)) {
                    0 -> if (useless) 0 else 1
                    1 -> if (useless) 0 else 6
                    else -> if (checkThreeSame) return 10 else if (this === inFrontOfWhom) -112 else 0
                }
                if (secretTask == Pioneer && this === inFrontOfWhom)
                    value += 11
            }
        }
    } else {
        val myColor = identity
        val enemyColor = (listOf(Red, Blue) - myColor).first()
        if (inFrontOfWhom.identity == myColor) { // 队友
            if (myColor in colors) {
                value += when (inFrontOfWhom.messageCards.count(myColor)) {
                    0 -> 10
                    1 -> 100
                    else -> if (checkThreeSame) return 10 else 1000
                }
            }
            if (Black in colors) {
                value -= when (inFrontOfWhom.messageCards.count(Black)) {
                    0 -> 1
                    1 -> 11
                    else -> if (checkThreeSame) return 10 else 112
                }
            }
        } else if (inFrontOfWhom.identity == enemyColor) { // 敌人
            if (enemyColor in colors) {
                value -= when (inFrontOfWhom.messageCards.count(enemyColor)) {
                    0 -> 10
                    1 -> 100
                    else -> if (checkThreeSame) return 10 else 1000
                }
            }
            if (Black in colors) {
                value += when (inFrontOfWhom.messageCards.count(Black)) {
                    0 -> 1
                    1 -> 11
                    else -> if (checkThreeSame) return 10 else 112
                }
            }
        }
    }
    return value.coerceIn(-600..600)
}

/**
 * 计算应该选择哪张情报传出的结果
 *
 * @param card 传出的牌
 * @param target 传出的目标
 * @param dir 传递方向
 * @param lockedPlayers 被锁定的玩家
 * @param value 价值
 */
class SendMessageCardResult(
    val card: Card,
    val target: Player,
    val dir: direction,
    var lockedPlayers: List<Player>,
    val value: Double
)

/**
 * 计算应该选择哪张情报传出
 *
 * @param availableCards 可以选择的牌，默认为该玩家的所有手牌
 */
fun Player.calSendMessageCard(
    whoseTurn: Player = this,
    availableCards: List<Card> = cards,
    isYuQinGuZong: Boolean = false,
): SendMessageCardResult {
    if (availableCards.isEmpty()) {
        logger.error("没有可用的情报牌，玩家手牌：${cards.joinToString()}")
        throw IllegalArgumentException("没有可用的情报牌")
    }
    var value = Double.NEGATIVE_INFINITY
    // 先随便填一个，反正后面要替换
    var result = SendMessageCardResult(availableCards[0], game!!.players[0]!!, Up, emptyList(), 0.0)

    fun calAveValue(card: Card, attenuation: Double, nextPlayerFunc: Player.() -> Player): Double {
        var sum = 0.0
        var n = 0.0
        var currentPlayer = nextPlayerFunc()
        var currentPercent = 1.0
        val canLock = card.canLock() || skills.any { it is MustLockOne || it is QiangYingXiaLing }
        if (attenuation == 0.0) { // 对于向上的情报，单独处理
            val nextPlayer = currentPlayer
            val nextValue = calculateMessageCardValue(whoseTurn, nextPlayer, card)
            val me = currentPlayer.nextPlayerFunc()
            val myValue = calculateMessageCardValue(whoseTurn, me, card)
            if (canLock && nextValue >= myValue) return nextValue.toDouble()
            val nextValue2 = nextPlayer.calculateMessageCardValue(whoseTurn, nextPlayer, card)
            val myValue2 = nextPlayer.calculateMessageCardValue(whoseTurn, me, card)
            return if (nextValue2 >= myValue2) nextValue * 0.9 + myValue * 0.1
            else myValue * 0.9 + nextValue * 0.1
        }
        while (true) {
            var m = currentPercent
            if (canLock) m *= m
            else if (isPartnerOrSelf(currentPlayer)) m *= 1.2
            sum += calculateMessageCardValue(whoseTurn, currentPlayer, card) * m
            n += m
            if (currentPlayer === this) break
            currentPlayer = currentPlayer.nextPlayerFunc()
            currentPercent *= attenuation
        }
        return sum / n
    }
    val notUp = game!!.isEarly && !isYuQinGuZong && identity != Black && (skills.any { it is LianLuo } ||
        availableCards.any { card ->
            !card.isPureBlack() &&
                when (card.direction) {
                    Left -> calAveValue(card, 0.7, Player::getNextLeftAlivePlayer) >= 0
                    Right -> calAveValue(card, 0.7, Player::getNextRightAlivePlayer) >= 0
                    else -> false
                }
        })
    for (card in availableCards.sortCards(identity, true)) {
        val removedCard = if (isYuQinGuZong) deleteMessageCard(card.id) else null
        if (!notUp && (card.direction == Up || skills.any { it is LianLuo })) {
            val (partner, enemy) = game!!.players.filter { it !== this && it!!.alive }.partition { isPartner(it!!) }
            for (target in partner.shuffled() + enemy.shuffled()) {
                val tmpValue = calAveValue(card, 0.0) { if (this === target) this@calSendMessageCard else target!! }
                if (tmpValue > value) {
                    value = tmpValue
                    result = SendMessageCardResult(card, target!!, Up, emptyList(), value)
                }
            }
        } else if (card.direction == Left || notUp && skills.any { it is LianLuo }) {
            val tmpValue = calAveValue(card, 0.7, Player::getNextLeftAlivePlayer)
            if (tmpValue > value) {
                value = tmpValue
                result = SendMessageCardResult(card, getNextLeftAlivePlayer(), Left, emptyList(), value)
            }
        } else if (card.direction == Right || notUp && skills.any { it is LianLuo }) {
            val tmpValue = calAveValue(card, 0.7, Player::getNextRightAlivePlayer)
            if (tmpValue > value) {
                value = tmpValue
                result = SendMessageCardResult(card, getNextRightAlivePlayer(), Right, emptyList(), value)
            }
        }
        removedCard?.let { messageCards.add(it) }
    }
    if (result.card.canLock() || skills.any { it is MustLockOne || it is QiangYingXiaLing }) {
        val removedCard = if (isYuQinGuZong) deleteMessageCard(result.card.id) else null
        var lockTarget: Player? = null
        when (result.dir) {
            Left -> {
                var maxValue = Int.MIN_VALUE
                val targets = game!!.sortedFrom(game!!.players.filter { it!!.alive }, location)
                for (i in listOf(0) + ((targets.size - 1) downTo 1)) {
                    val target = targets[i]
                    val v = calculateMessageCardValue(whoseTurn, target, result.card, sender = this)
                    if (v > maxValue) {
                        maxValue = v
                        lockTarget = target
                    }
                }
            }

            Right -> {
                var maxValue = Int.MIN_VALUE
                val targets = game!!.sortedFrom(game!!.players.filter { it!!.alive }, location)
                for (target in targets) {
                    val v = calculateMessageCardValue(whoseTurn, target, result.card, sender = this)
                    if (v > maxValue) {
                        maxValue = v
                        lockTarget = target
                    }
                }
            }

            else -> { // 向上的情报
                val v1 = calculateMessageCardValue(whoseTurn, this, result.card, sender = this)
                val v2 = calculateMessageCardValue(whoseTurn, result.target, result.card, sender = this)
                // 如果不同，则锁分数高的。如果相同，则不锁
                if (v1 > v2) lockTarget = this
                else if (v1 < v2) lockTarget = result.target
            }
        }
        lockTarget?.let {
            // 如果刚开局，就不锁自己。如果是左右情报，就不锁自己。如果传递目标不是队友，就不锁自己
            if (!game!!.isEarly && result.dir == Up && isPartner(result.target) || it !== this)
                result.lockedPlayers = listOf(it)
        }
        removedCard?.let { messageCards.add(it) }
    }
    logger.debug("计算结果：${result.card}(cardId:${result.card.id})传递给${result.target}，方向是${result.dir}，分数为${result.value}")
    return result
}

/**
 * 是否要救人
 */
fun Player.wantToSave(whoseTurn: Player, whoDie: Player): Boolean {
    // 如果死亡的是老汉且有情报
    if (whoDie.skills.any { it is RuGui } && whoDie.messageCards.isNotEmpty()) {
        // 如果老汉和当前回合角色是同一身份+老汉情报区有该颜色情报+当前回合角色听牌
        if (whoDie !== whoseTurn && whoDie.identity == whoseTurn.identity &&
            !whoDie.messageCards.filter(whoDie.identity).isEmpty() &&
            whoseTurn.messageCards.count(whoseTurn.identity) == 2) {
            // 如果自己也是同一阵营，则不救
            if (isPartnerOrSelf(whoDie)) {
                return false
            }
            // 如果自己不是同一阵营，则救（防止发动技能后敌方胜利）
            return true
        }
    }
    // 如果死亡的是李宁玉且有手牌
    if (whoDie.roleFaceUp && whoDie.skills.any { it is YiXin } && whoDie.cards.isNotEmpty()) {
        // 如果李宁玉的队友听牌
        if (whoDie.game!!.players.any {
                it!!.alive && it !== whoDie && it.identity == whoDie.identity && it.messageCards.count(whoDie.identity) == 2
            }) {
            // 如果自己也是同一阵营，则不救
            if (isPartnerOrSelf(whoDie)) {
                val stealer = game!!.players.find { it!!.alive && it.identity == Black && it.secretTask == Stealer }
                // 特殊情况：当前回合是篡夺者，则救
                return whoseTurn === stealer
            }
            // 如果自己不是同一阵营，则救（防止发动技能后敌方胜利）
            return true
        }
    }
    var save = isPartnerOrSelf(whoDie)
    var notSave = false
    val killer = game!!.players.find { it!!.alive && it.identity == Black && it.secretTask == Killer }
    val pioneer = game!!.players.find { it!!.alive && it.identity == Black && it.secretTask == Pioneer }
    val sweeper = game!!.players.find { it!!.alive && it.identity == Black && it.secretTask == Sweeper }
    val stealer = game!!.players.find { it!!.alive && it.identity == Black && it.secretTask == Stealer }
    if (killer === whoseTurn && whoDie.messageCards.countTrueCard() >= 2) {
        if (killer === this) notSave = true
        save = save || killer !== this
    }
    if (pioneer === whoDie && whoDie.messageCards.countTrueCard() >= 1) {
        if (pioneer === this && whoseTurn !== stealer) notSave = true
        if (stealer === this && whoseTurn === this) notSave = true
        save = save || pioneer !== this
    }
    if (sweeper != null && whoDie.messageCards.run { count(Red) <= 1 && count(Blue) <= 1 }) {
        if (sweeper === this && whoseTurn !== stealer) notSave = true
        if (stealer === this && whoseTurn === this) notSave = true
        save = save || sweeper !== this
    }
    return !notSave && save
}

class FightPhaseResult(
    val cardType: card_type,
    val card: Card,
    val wuDaoTarget: Player?,
    val value: Int,
    val deltaValue: Int,
    val convertCardSkill: ConvertCardSkill?
)

fun Player.calFightPhase(e: FightPhaseIdle, whoUse: Player = this, availableCards: List<Card> = this.cards): FightPhaseResult? {
    val order = mutableListOf(Wu_Dao, Jie_Huo, Diao_Bao)
    if (skills.any { it is YouDao || it is JiangJiJiuJi }) {
        if (roleFaceUp) {
            order[order.indexOf(Wu_Dao)] = order[0]
            order[0] = Wu_Dao
        } else {
            order[order.indexOf(Wu_Dao)] = order[2]
            order[2] = Wu_Dao
        }
    } else if (skills.any { it is ShunShiErWei || it is ShenCang }) {
        if (roleFaceUp) {
            order[order.indexOf(Jie_Huo)] = order[0]
            order[0] = Jie_Huo
        } else {
            order[order.indexOf(Jie_Huo)] = order[2]
            order[2] = Jie_Huo
        }
    } else if (skills.any { it is HuanRi }) {
        if (roleFaceUp) {
            order[order.indexOf(Diao_Bao)] = order[0]
            order[0] = Diao_Bao
        } else {
            order[order.indexOf(Diao_Bao)] = order[2]
            order[2] = Diao_Bao
        }
    }
    val oldValue = calculateMessageCardValue(e.whoseTurn, e.inFrontOfWhom, e.messageCard, sender = e.sender)
    var value = oldValue
    var result: FightPhaseResult? = null
    val cards = availableCards.sortCards(identity)
    for (cardType in order) {
        !whoUse.cannotPlayCard(cardType) || continue
        loop@ for (card in cards) {
            val (ok, convertCardSkill) = whoUse.canUseCardTypes(cardType, card, whoUse !== this)
            ok || continue
            when (cardType) {
                Jie_Huo -> {
                    val newValue = calculateMessageCardValue(e.whoseTurn, whoUse, e.messageCard, sender = e.sender)
                    if (newValue > value) {
                        result = FightPhaseResult(
                            cardType,
                            card,
                            null,
                            newValue,
                            newValue - oldValue,
                            convertCardSkill
                        )
                        value = newValue
                    }
                    break@loop
                }

                Diao_Bao -> {
                    val newValue = calculateMessageCardValue(e.whoseTurn, e.inFrontOfWhom, card, sender = e.sender)
                    if (newValue > value) {
                        result = FightPhaseResult(
                            cardType,
                            card,
                            null,
                            newValue,
                            newValue - oldValue,
                            convertCardSkill
                        )
                        value = newValue
                    }
                }

                else -> { // Wu_Dao
                    val calLeft = {
                        val left = e.inFrontOfWhom.getNextLeftAlivePlayer()
                        val newValueLeft =
                            calculateMessageCardValue(e.whoseTurn, left, e.messageCard, sender = e.sender)
                        if (newValueLeft > value) {
                            result = FightPhaseResult(
                                cardType,
                                card,
                                left,
                                newValueLeft,
                                newValueLeft - oldValue,
                                convertCardSkill
                            )
                            value = newValueLeft
                        }
                    }
                    val calRight = {
                        val right = e.inFrontOfWhom.getNextRightAlivePlayer()
                        val newValueRight =
                            calculateMessageCardValue(e.whoseTurn, right, e.messageCard, sender = e.sender)
                        if (newValueRight > value) {
                            result = FightPhaseResult(
                                cardType,
                                card,
                                right,
                                newValueRight,
                                newValueRight - oldValue,
                                convertCardSkill
                            )
                            value = newValueRight
                        }
                    }
                    if (Random.nextBoolean()) {
                        calLeft()
                        calRight()
                    } else {
                        calRight()
                        calLeft()
                    }
                    break@loop
                }
            }
        }
    }
    return result
}
