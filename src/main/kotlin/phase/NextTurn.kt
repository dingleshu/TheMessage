package com.fengsheng.phase

import com.fengsheng.*
import com.fengsheng.card.countTrueCard
import com.fengsheng.protos.Common.color.Black
import com.fengsheng.protos.Common.secret_task.Disturber
import com.fengsheng.protos.unknownWaitingToc
import com.fengsheng.skill.InvalidSkill
import com.fengsheng.skill.OneTurnSkill
import com.fengsheng.skill.changeGameResult
import org.apache.logging.log4j.kotlin.logger
import java.util.concurrent.TimeUnit

/**
 * 即将跳转到下一回合时
 *
 * @param whoseTurn 当前回合的玩家（不是下回合的玩家）
 */
data class NextTurn(override val whoseTurn: Player) : ProcessFsm() {
    override fun onSwitch() {
        whoseTurn.game!!.addEvent(TurnEndEvent(whoseTurn))
    }

    override fun resolve0(): ResolveResult? {
        val game = whoseTurn.game!!
        if (checkDisturberWin(game))
            return ResolveResult(null, false)
        var whoseTurn = whoseTurn.location
        repeat(100) { // 防止死循环
            whoseTurn = (whoseTurn + 1) % game.players.size
            val player = game.players[whoseTurn]!!
            if (player.alive) {
                game.mainPhaseAlreadyNotify = false
                game.players.forEach {
                    it!!.resetSkillUseCount()
                    it.useCardThisTurn = false
                    it.canWeiBiCardIds.removeIf { cid ->
                        !game.players.any { p -> p!!.alive && p !== it && p.cards.any { c -> c.id == cid } }
                    }
                }
                InvalidSkill.reset(game)
                OneTurnSkill.reset(game)
                game.players.send { unknownWaitingToc { } }
                GameExecutor.post(game, { game.resolve(DrawPhase(player)) }, 500, TimeUnit.MILLISECONDS)
                return null
            }
        }
        return null
    }

    private fun checkDisturberWin(game: Game): Boolean { // 无需判断簒夺者，因为簒夺者、搅局者都要求是自己回合
        val players = game.players.filterNotNull().filter { !it.lose }
        if (whoseTurn.identity != Black || whoseTurn.secretTask != Disturber) return false // 不是搅局者
        if (players.any { it !== whoseTurn && it.alive && it.messageCards.countTrueCard() < 2 }) return false
        val declaredWinner = arrayListOf(whoseTurn)
        val winner = arrayListOf(whoseTurn)
        game.changeGameResult(whoseTurn, declaredWinner, winner)
        logger.info("${declaredWinner.joinToString()}宣告胜利，胜利者有${winner.joinToString()}")
        game.players.send { unknownWaitingToc { } }
        GameExecutor.post(game, {
            game.allPlayerSetRoleFaceUp()
            game.end(declaredWinner, winner)
        }, 1, TimeUnit.SECONDS)
        return true
    }
}
