package com.fengsheng.skill

import com.fengsheng.*
import com.fengsheng.phase.FightPhaseIdle
import com.fengsheng.phase.NextTurn
import com.fengsheng.phase.OnReceiveCard
import com.fengsheng.protos.Role.skill_ding_lun_tos
import com.fengsheng.protos.skillDingLunToc
import com.fengsheng.protos.skillDingLunTos
import com.google.protobuf.GeneratedMessage
import org.apache.logging.log4j.kotlin.logger
import java.util.concurrent.TimeUnit

/**
 * 李书云技能【定论】：争夺阶段，若情报在你面前，可以翻开此角色，直接成功接收，但若因此达成同色三张，则改为入手。
 */
class DingLun : ActiveSkill {
    override val skillId = SkillId.DING_LUN

    override val isInitialSkill = true

    override fun canUse(fightPhase: FightPhaseIdle, r: Player): Boolean = !r.roleFaceUp && fightPhase.inFrontOfWhom === r

    override fun executeProtocol(g: Game, r: Player, message: GeneratedMessage) {
        val fsm = g.fsm as? FightPhaseIdle
        if (r !== fsm?.whoseFightTurn) {
            logger.error("不是你发技能的时机")
            r.sendErrorMessage("不是你发技能的时机")
            return
        }
        if (r !== fsm.inFrontOfWhom) {
            logger.error("情报不在你面前，不能发动[定论]")
            r.sendErrorMessage("情报不在你面前，不能发动[定论]")
            return
        }
        if (r.roleFaceUp) {
            logger.error("你现在正面朝上，不能发动[定论]")
            r.sendErrorMessage("你现在正面朝上，不能发动[定论]")
            return
        }
        val pb = message as skill_ding_lun_tos
        if (r is HumanPlayer && !r.checkSeq(pb.seq)) {
            logger.error("操作太晚了, required Seq: ${r.seq}, actual Seq: ${pb.seq}")
            r.sendErrorMessage("操作太晚了")
            return
        }
        r.incrSeq()
        r.addSkillUseCount(skillId)
        g.playerSetRoleFaceUp(r, true)
        logger.info("${r}发动了[定论]")
        val joinIntoHand = r.checkThreeSameMessageCard(fsm.messageCard)
        g.players.send {
            skillDingLunToc {
                playerId = it.getAlternativeLocation(r.location)
                card = fsm.messageCard.toPbCard()
                this.joinIntoHand = joinIntoHand
            }
        }
        if (joinIntoHand) {
            logger.info("${r}将${fsm.messageCard}加入了手牌")
            g.players.forEach { it!!.canWeiBiCardIds.add(fsm.messageCard.id) }
            r.cards.add(fsm.messageCard)
            g.resolve(NextTurn(fsm.whoseTurn))
        } else g.resolve(OnReceiveCard(fsm.whoseTurn, fsm.sender, fsm.messageCard, r))
    }

    companion object {
        fun ai(e: FightPhaseIdle, skill: ActiveSkill): Boolean {
            val player = e.whoseFightTurn
            val whoseTurn = e.whoseTurn
            !player.roleFaceUp || return false
            player === e.inFrontOfWhom || return false
            // 如果正常获得情报会赢，不发动技能
            !player.willWin(whoseTurn, e.inFrontOfWhom, e.messageCard) || return false
            // 有收益的情况下才会发动技能
            val v = player.calculateMessageCardValue(whoseTurn, player, e.messageCard, true, player)
            v >= 0 || return false
            GameExecutor.post(e.whoseFightTurn.game!!, {
                skill.executeProtocol(e.whoseFightTurn.game!!, e.whoseFightTurn, skillDingLunTos { })
            }, 1, TimeUnit.SECONDS)
            return true
        }
    }
}
