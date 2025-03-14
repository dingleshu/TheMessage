package com.fengsheng.skill

import com.fengsheng.*
import com.fengsheng.protos.Common.color
import com.fengsheng.protos.Fengsheng.end_receive_phase_tos
import com.fengsheng.protos.Role.skill_jin_shen_tos
import com.fengsheng.protos.skillJinShenToc
import com.fengsheng.protos.skillJinShenTos
import com.google.protobuf.GeneratedMessage
import org.apache.logging.log4j.kotlin.logger
import java.util.concurrent.TimeUnit

/**
 * 金生火技能【谨慎】：你接收双色情报后，可以用一张手牌与该情报面朝上互换。
 */
class JinShen : TriggeredSkill {
    override val skillId = SkillId.JIN_SHEN

    override val isInitialSkill = true

    override fun execute(g: Game, askWhom: Player): ResolveResult? {
        val event = g.findEvent<ReceiveCardEvent>(this) { event ->
            askWhom === event.inFrontOfWhom || return@findEvent false
            event.messageCard.colors.size == 2 || return@findEvent false
            askWhom.findMessageCard(event.messageCard.id) != null
        } ?: return null
        return ResolveResult(ExecuteJinShen(g.fsm!!, event), true)
    }

    private data class ExecuteJinShen(val fsm: Fsm, val event: ReceiveCardEvent) : WaitingFsm {
        override val whoseTurn: Player
            get() = fsm.whoseTurn

        override fun resolve(): ResolveResult? {
            for (p in event.whoseTurn.game!!.players)
                p!!.notifyReceivePhase(event.whoseTurn, event.inFrontOfWhom, event.messageCard, event.inFrontOfWhom)
            return null
        }

        override fun resolveProtocol(player: Player, message: GeneratedMessage): ResolveResult? {
            if (player !== event.inFrontOfWhom) {
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
            if (message !is skill_jin_shen_tos) {
                logger.error("错误的协议")
                player.sendErrorMessage("错误的协议")
                return null
            }
            val r = event.inFrontOfWhom
            val g = r.game!!
            if (r is HumanPlayer && !r.checkSeq(message.seq)) {
                logger.error("操作太晚了, required Seq: ${r.seq}, actual Seq: ${message.seq}")
                r.sendErrorMessage("操作太晚了")
                return null
            }
            val card = r.findCard(message.cardId)
            if (card == null) {
                logger.error("没有这张卡")
                player.sendErrorMessage("没有这张卡")
                return null
            }
            r.incrSeq()
            logger.info("${r}发动了[谨慎]，用${card}交换了原情报${event.messageCard}")
            g.players.forEach { it!!.canWeiBiCardIds.add(event.messageCard.id) }
            val messageCard = event.messageCard
            r.deleteCard(card.id)
            r.deleteMessageCard(messageCard.id)
            r.messageCards.add(card)
            r.cards.add(messageCard)
            g.players.send {
                skillJinShenToc {
                    this.card = card.toPbCard()
                    playerId = it.getAlternativeLocation(r.location)
                    messageCardId = event.messageCard.id
                }
            }
            event.messageCard = card
            return ResolveResult(fsm, true)
        }
    }

    companion object {
        fun ai(fsm0: Fsm): Boolean {
            if (fsm0 !is ExecuteJinShen) return false
            val p = fsm0.event.inFrontOfWhom
            val card = p.cards.find { !it.colors.contains(color.Black) } ?: return false
            GameExecutor.post(p.game!!, {
                p.game!!.tryContinueResolveProtocol(p, skillJinShenTos { cardId = card.id })
            }, 2, TimeUnit.SECONDS)
            return true
        }
    }
}
