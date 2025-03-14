package com.fengsheng.skill

import com.fengsheng.*
import com.fengsheng.card.Card
import com.fengsheng.card.PlayerAndCard
import com.fengsheng.protos.*
import com.fengsheng.protos.Common.color
import com.fengsheng.protos.Role.skill_jian_ren_a_tos
import com.fengsheng.protos.Role.skill_jian_ren_b_tos
import com.google.protobuf.GeneratedMessage
import org.apache.logging.log4j.kotlin.logger
import java.util.concurrent.TimeUnit

/**
 * 吴志国技能【坚韧】：你接收黑色情报后，可以展示牌堆顶的一张牌，若是黑色牌，则将展示的牌加入你的手牌，并从一名角色的情报区弃置一张黑色情报。
 */
class JianRen : TriggeredSkill {
    override val skillId = SkillId.JIAN_REN

    override val isInitialSkill = true

    override fun execute(g: Game, askWhom: Player): ResolveResult? {
        val event = g.findEvent<ReceiveCardEvent>(this) { event ->
            askWhom === event.inFrontOfWhom || return@findEvent false
            event.messageCard.isBlack()
        } ?: return null
        return ResolveResult(ExecuteJianRenA(g.fsm!!, event), true)
    }

    private data class ExecuteJianRenA(val fsm: Fsm, val event: ReceiveCardEvent) : WaitingFsm {
        override val whoseTurn: Player
            get() = fsm.whoseTurn

        override fun resolve(): ResolveResult? {
            for (p in event.whoseTurn.game!!.players)
                p!!.notifyReceivePhase(event.whoseTurn, event.inFrontOfWhom, event.messageCard, event.inFrontOfWhom)
            return null
        }

        override fun resolveProtocol(player: Player, message: GeneratedMessage): ResolveResult? {
            val r = event.inFrontOfWhom
            if (player !== r) {
                logger.error("不是你发技能的时机")
                player.sendErrorMessage("不是你发技能的时机")
                return null
            }
            if (message is Fengsheng.end_receive_phase_tos) {
                if (player is HumanPlayer && !player.checkSeq(message.seq)) {
                    logger.error("操作太晚了, required Seq: ${player.seq}, actual Seq: ${message.seq}")
                    player.sendErrorMessage("操作太晚了")
                    return null
                }
                player.incrSeq()
                return ResolveResult(fsm, true)
            }
            if (message !is skill_jian_ren_a_tos) {
                logger.error("错误的协议")
                player.sendErrorMessage("错误的协议")
                return null
            }
            val g = r.game!!
            if (r is HumanPlayer && !r.checkSeq(message.seq)) {
                logger.error("操作太晚了, required Seq: ${r.seq}, actual Seq: ${message.seq}")
                r.sendErrorMessage("操作太晚了")
                return null
            }
            val cards = g.deck.peek(1)
            if (cards.isEmpty()) {
                logger.error("牌堆没有牌了")
                player.sendErrorMessage("牌堆没有牌了")
                return null
            }
            r.incrSeq()
            logger.info("${r}发动了[坚韧]，展示了${cards[0]}")
            return ResolveResult(ExecuteJianRenB(fsm, event, cards), true)
        }
    }

    private data class ExecuteJianRenB(val fsm: Fsm, val event: ReceiveCardEvent, val cards: List<Card>) : WaitingFsm {
        override val whoseTurn: Player
            get() = fsm.whoseTurn

        override fun resolve(): ResolveResult? {
            val r = event.inFrontOfWhom
            val autoChoose = r.chooseBlackMessageCard()
            val card = cards[0]
            val isBlack = card.colors.contains(color.Black)
            if (isBlack) {
                r.game!!.deck.draw(1)
                r.cards.add(card)
                logger.info("${r}将${card}加入了手牌")
                r.game!!.players.forEach { it!!.canWeiBiCardIds.add(card.id) }
            }
            val g = r.game!!
            g.players.send { p ->
                skillJianRenAToc {
                    playerId = p.getAlternativeLocation(r.location)
                    this.card = card.toPbCard()
                    if (isBlack && autoChoose != null) {
                        waitingSecond = g.waitSecond
                        if (p === r) {
                            val seq2 = p.seq
                            seq = seq2
                            p.timeout = GameExecutor.post(g, {
                                if (p.checkSeq(seq2))
                                    p.game!!.tryContinueResolveProtocol(p, skillJianRenBTos {
                                        targetPlayerId = p.getAlternativeLocation(autoChoose.player.location)
                                        cardId = autoChoose.card.id
                                        seq = seq2
                                    })
                            }, p.getWaitSeconds(waitingSecond + 2).toLong(), TimeUnit.SECONDS)
                        }
                    }
                }
            }
            if (r is RobotPlayer && isBlack && autoChoose != null) {
                GameExecutor.post(g, {
                    r.game!!.tryContinueResolveProtocol(r, skillJianRenBTos {
                        targetPlayerId = r.getAlternativeLocation(autoChoose.player.location)
                        cardId = autoChoose.card.id
                    })
                }, 3, TimeUnit.SECONDS)
            }
            return if (isBlack && autoChoose != null) null else ResolveResult(fsm, true)
        }

        override fun resolveProtocol(player: Player, message: GeneratedMessage): ResolveResult? {
            if (player !== event.inFrontOfWhom) {
                logger.error("不是你发技能的时机")
                player.sendErrorMessage("不是你发技能的时机")
                return null
            }
            if (message !is skill_jian_ren_b_tos) {
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
            if (message.targetPlayerId < 0 || message.targetPlayerId >= g.players.size) {
                logger.error("目标错误")
                player.sendErrorMessage("目标错误")
                return null
            }
            val target = g.players[r.getAbstractLocation(message.targetPlayerId)]!!
            if (!target.alive) {
                logger.error("目标已死亡")
                player.sendErrorMessage("目标已死亡")
                return null
            }
            val messageCard = target.findMessageCard(message.cardId)
            if (messageCard == null) {
                logger.error("没有这张情报")
                player.sendErrorMessage("没有这张情报")
                return null
            }
            if (!messageCard.colors.contains(color.Black)) {
                logger.error("目标情报不是黑色的")
                player.sendErrorMessage("目标情报不是黑色的")
                return null
            }
            r.incrSeq()
            logger.info("${r}弃掉了${target}面前的$messageCard")
            target.deleteMessageCard(messageCard.id)
            g.deck.discard(messageCard)
            g.players.send {
                skillJianRenBToc {
                    playerId = it.getAlternativeLocation(r.location)
                    targetPlayerId = it.getAlternativeLocation(target.location)
                    cardId = messageCard.id
                }
            }
            return ResolveResult(fsm, true)
        }
    }

    companion object {
        private fun Player.chooseBlackMessageCard(): PlayerAndCard? {
            for (card in messageCards) {
                if (card.colors.contains(color.Black)) return PlayerAndCard(this, card)
            }
            for (p in game!!.players) {
                if (p !== this && p!!.alive) {
                    for (card in p.messageCards) {
                        if (card.colors.contains(color.Black)) return PlayerAndCard(p, card)
                    }
                }
            }
            return null
        }

        fun ai(fsm0: Fsm): Boolean {
            if (fsm0 !is ExecuteJianRenA) return false
            val p: Player = fsm0.event.inFrontOfWhom
            GameExecutor.post(p.game!!, {
                p.game!!.tryContinueResolveProtocol(p, skillJianRenATos { })
            }, 2, TimeUnit.SECONDS)
            return true
        }
    }
}
