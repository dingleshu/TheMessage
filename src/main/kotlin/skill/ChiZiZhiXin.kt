package com.fengsheng.skill

import com.fengsheng.*
import com.fengsheng.RobotPlayer.Companion.sortCards
import com.fengsheng.card.Card
import com.fengsheng.protos.Fengsheng.end_receive_phase_tos
import com.fengsheng.protos.Role.skill_chi_zi_zhi_xin_a_tos
import com.fengsheng.protos.Role.skill_chi_zi_zhi_xin_b_tos
import com.fengsheng.protos.skillChiZiZhiXinAToc
import com.fengsheng.protos.skillChiZiZhiXinATos
import com.fengsheng.protos.skillChiZiZhiXinBToc
import com.fengsheng.protos.skillChiZiZhiXinBTos
import com.google.protobuf.GeneratedMessage
import org.apache.logging.log4j.kotlin.logger
import java.util.concurrent.TimeUnit

/**
 * SP小九技能【赤子之心】：你传出的非黑色情报被其他角色接收后，你可以摸两张牌，或从手牌中选择一张含有该情报颜色的牌，将其置入你的情报区。
 */
class ChiZiZhiXin : TriggeredSkill {
    override val skillId = SkillId.CHI_ZI_ZHI_XIN

    override val isInitialSkill = true

    override fun execute(g: Game, askWhom: Player): ResolveResult? {
        val event = g.findEvent<ReceiveCardEvent>(this) { event ->
            askWhom === event.sender || return@findEvent false
            !event.messageCard.isBlack() || return@findEvent false
            askWhom !== event.inFrontOfWhom
        } ?: return null
        return ResolveResult(ExecuteChiZiZhiXinA(g.fsm!!, event), true)
    }

    private data class ExecuteChiZiZhiXinA(val fsm: Fsm, val event: ReceiveCardEvent) : WaitingFsm {
        override val whoseTurn: Player
            get() = fsm.whoseTurn

        override fun resolve(): ResolveResult? {
            for (p in event.sender.game!!.players)
                p!!.notifyReceivePhase(
                    event.whoseTurn,
                    event.inFrontOfWhom,
                    event.messageCard,
                    event.sender
                )
            return null
        }

        override fun resolveProtocol(player: Player, message: GeneratedMessage): ResolveResult? {
            if (player !== event.sender) {
                logger.error("不是你发技能的时机")
                player.sendErrorMessage("不是你发技能的时机")
                return null
            }
            if (message is end_receive_phase_tos) {
                if (player is HumanPlayer && !player.checkSeq(message.seq)) {
                    logger.error("操作太晚了, required Seq: ${player.seq}, actual Seq: ${message.seq}")
                    player.sendErrorMessage("操作太晚了")
                    return null
                }
                player.incrSeq()
                return ResolveResult(fsm, true)
            }
            if (message !is skill_chi_zi_zhi_xin_a_tos) {
                logger.error("错误的协议")
                player.sendErrorMessage("错误的协议")
                return null
            }
            val r = event.sender
            if (r is HumanPlayer && !r.checkSeq(message.seq)) {
                logger.error("操作太晚了, required Seq: ${r.seq}, actual Seq: ${message.seq}")
                r.sendErrorMessage("操作太晚了")
                return null
            }
            player.incrSeq()
            return ResolveResult(ExecuteChiZiZhiXinB(fsm, event), true)
        }
    }

    private data class ExecuteChiZiZhiXinB(val fsm: Fsm, val event: ReceiveCardEvent) : WaitingFsm {
        override val whoseTurn: Player
            get() = fsm.whoseTurn

        override fun resolve(): ResolveResult? {
            val r = event.sender
            r.game!!.players.send { p ->
                skillChiZiZhiXinAToc {
                    playerId = p.getAlternativeLocation(r.location)
                    messageCard = event.messageCard.toPbCard()
                    waitingSecond = r.game!!.waitSecond
                    if (p === r) {
                        val seq = r.seq
                        this.seq = seq
                        p.timeout = GameExecutor.post(r.game!!, {
                            if (p.checkSeq(seq)) {
                                p.game!!.tryContinueResolveProtocol(p, skillChiZiZhiXinBTos {
                                    drawCard = true
                                    this.seq = seq
                                })
                            }
                        }, p.getWaitSeconds(waitingSecond + 2).toLong(), TimeUnit.SECONDS)
                    }
                }
            }
            if (r is RobotPlayer) {
                GameExecutor.post(r.game!!, {
                    var value = 30
                    var card: Card? = null
                    for (c in r.cards.filter { it.hasSameColor(event.messageCard) }.sortCards(r.identity, true)) {
                        val v = r.calculateMessageCardValue(event.whoseTurn, event.sender, c)
                        if (v > value) {
                            value = v
                            card = c
                        }
                    }
                    r.game!!.tryContinueResolveProtocol(r, skillChiZiZhiXinBTos {
                        drawCard = true
                        card?.let {
                            drawCard = false
                            cardId = it.id
                        }
                    })
                }, 3, TimeUnit.SECONDS)
            }
            return null
        }

        override fun resolveProtocol(player: Player, message: GeneratedMessage): ResolveResult? {
            if (player !== event.sender) {
                logger.error("不是你发技能的时机")
                player.sendErrorMessage("不是你发技能的时机")
                return null
            }
            if (message !is skill_chi_zi_zhi_xin_b_tos) {
                logger.error("错误的协议")
                player.sendErrorMessage("错误的协议")
                return null
            }
            val r = event.sender
            if (r is HumanPlayer && !r.checkSeq(message.seq)) {
                logger.error("操作太晚了, required Seq: ${r.seq}, actual Seq: ${message.seq}")
                r.sendErrorMessage("操作太晚了")
                return null
            }
            var card: Card? = null
            if (!message.drawCard) {
                card = r.findCard(message.cardId)
                if (card == null) {
                    logger.error("没有这张卡")
                    player.sendErrorMessage("没有这张卡")
                    return null
                }
                if (!card.hasSameColor(event.messageCard)) {
                    logger.error("你选择的牌没有情报牌的颜色")
                    player.sendErrorMessage("你选择的牌没有情报牌的颜色")
                    return null
                }
                logger.info("${r}发动了[赤子之心]，将手牌中的${card}置入自己的情报区")
                r.incrSeq()
                r.deleteCard(card.id)
                r.messageCards.add(card)
                r.game!!.addEvent(AddMessageCardEvent(event.whoseTurn))
            } else {
                logger.info("${r}发动了[赤子之心]，选择了摸两张牌")
                r.incrSeq()
            }
            r.game!!.players.send { p ->
                skillChiZiZhiXinBToc {
                    playerId = p.getAlternativeLocation(r.location)
                    drawCard = message.drawCard
                    card?.let { this.card = it.toPbCard() }
                }
            }
            if (message.drawCard) r.draw(2)
            return ResolveResult(fsm, true)
        }
    }

    companion object {
        fun ai(fsm0: Fsm): Boolean {
            if (fsm0 !is ExecuteChiZiZhiXinA) return false
            val p = fsm0.event.sender
            GameExecutor.post(p.game!!, {
                p.game!!.tryContinueResolveProtocol(p, skillChiZiZhiXinATos { })
            }, 1, TimeUnit.SECONDS)
            return true
        }
    }
}
