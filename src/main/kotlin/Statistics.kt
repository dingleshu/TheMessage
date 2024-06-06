package com.fengsheng

import com.fengsheng.ScoreFactory.addScore
import com.fengsheng.protos.Common.*
import com.fengsheng.protos.getRecordListToc
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.logging.log4j.kotlin.logger
import java.awt.image.BufferedImage
import java.io.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.fixedRateTimer
import kotlin.math.ceil
import kotlin.random.Random

object Statistics {
    private val pool = Channel<() -> Unit>(Channel.UNLIMITED)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").apply {
        timeZone = TimeZone.getTimeZone("GMT+8:00")
    }
    private val playerInfoMap = ConcurrentHashMap<String, PlayerInfo>()
    private val robotInfoMap = ConcurrentHashMap<String, RobotInfo>()
    private val totalWinCount = AtomicInteger()
    private val totalGameCount = AtomicInteger()
    private val trialStartTime = ConcurrentHashMap<String, Long>()
    val rankList25 = AtomicReference<String>()
    val rankListImage = AtomicReference<BufferedImage>()

    init {
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            while (true) {
                val f = pool.receive()
                withContext(Dispatchers.IO) { f() }
            }
        }

        fixedRateTimer(daemon = true, initialDelay = 12 * 3600 * 1000, period = 24 * 3600 * 1000) {
            val file = File("playerInfo.csv")
            if (file.exists()) file.copyTo(File("playerInfo.csv.bak"), true)
        }
    }

    fun add(records: List<Record>) {
        ScoreFactory.addWinCount(records)
        pool.trySend {
            try {
                val time = dateFormat.format(Date())
                val sb = StringBuilder()
                for (r in records) {
                    sb.append(r.role).append(',')
                    sb.append(r.isWinner).append(',')
                    sb.append(r.identity).append(',')
                    sb.append(if (r.identity == color.Black) r.task.toString() else "").append(',')
                    sb.append(r.totalPlayerCount).append(',')
                    sb.append(time).append('\n')
                }
                writeFile("stat.csv", sb.toString().toByteArray(), true)
            } catch (e: Exception) {
                logger.error("execute task failed", e)
            }
        }
    }

    fun addPlayerGameCount(playerGameResultList: List<PlayerGameResult>) {
        try {
            val now = System.currentTimeMillis()
            var win = 0
            var game = 0
            var updateTrial = false
            for (count in playerGameResultList) {
                if (count.isWin) {
                    win++
                    if (trialStartTime.remove(count.playerName) != null) updateTrial = true
                }
                game++
                playerInfoMap.computeIfPresent(count.playerName) { _, v ->
                    val addWin = if (count.isWin) 1 else 0
                    v.copy(winCount = v.winCount + addWin, gameCount = v.gameCount + 1, lastTime = now)
                }
            }
            totalWinCount.addAndGet(win)
            totalGameCount.addAndGet(game)
            pool.trySend {
                savePlayerInfo()
                if (updateTrial) saveTrials()
            }
        } catch (e: Exception) {
            logger.error("add player game count failed: ", e)
        }
    }

    fun register(name: String): Boolean {
        val result = playerInfoMap.putIfAbsent(name, PlayerInfo(name, 0, "", 0, 0, 0, "", 0, 10)) == null
        if (result) pool.trySend(::savePlayerInfo)
        return result
    }

    fun login(name: String, pwd: String?): PlayerInfo {
        val password = try {
            if (pwd.isNullOrEmpty()) "" else md5(name + pwd)
        } catch (e: NoSuchAlgorithmException) {
            logger.error("md5加密失败", e)
            throw Exception("内部错误，登录失败")
        }
        var changed = false
        val playerInfo = playerInfoMap.computeIfPresent(name) { _, v ->
            if (v.password.isEmpty() && password.isNotEmpty()) {
                changed = true
                v.copy(password = password)
            } else v
        } ?: throw Exception("用户名或密码错误，你可以在群里输入“注册”")
        if (changed) pool.trySend(::savePlayerInfo)
        if (password != playerInfo.password) throw Exception("用户名或密码错误，你可以在群里输入“注册”")
        val forbidLeft = playerInfo.forbidUntil - System.currentTimeMillis()
        if (forbidLeft > 0) throw Exception("你已被禁止登录，剩余${ceil(forbidLeft / 3600000.0).toInt()}小时")
        return playerInfo
    }

    fun forbidPlayer(name: String, hours: Int): Boolean {
        val forbidUntil = System.currentTimeMillis() + hours * 3600000L
        var changed = false
        playerInfoMap.computeIfPresent(name) { _, v ->
            changed = true
            v.copy(forbidUntil = forbidUntil)
        }
        if (changed) pool.trySend(::savePlayerInfo)
        return changed
    }

    fun releasePlayer(name: String): Boolean {
        var changed = false
        playerInfoMap.computeIfPresent(name) { _, v ->
            changed = true
            v.copy(forbidUntil = 0)
        }
        if (changed) pool.trySend(::savePlayerInfo)
        return changed
    }

    fun getPlayerInfo(name: String) = playerInfoMap[name]
    fun getScore(name: String) = playerInfoMap[name]?.score
    fun getScore(player: Player) =
        if (player is HumanPlayer) getScore(player.playerName) else robotInfoMap[player.playerName]?.score
    fun getScore2(player: Player) =
        if (player is HumanPlayer) playerInfoMap[player.playerName]?.scoreWithDecay else robotInfoMap[player.playerName]?.score
    fun getEnergy(name: String) = playerInfoMap[name]?.energy ?: 0
    fun addEnergy(name: String, energy: Int): Boolean {
        return playerInfoMap.computeIfPresent(name) { _, v ->
            v.copy(energy = (v.energy + energy).coerceAtLeast(0))
        } != null
    }

    fun updateTitle(name: String, title: String): Boolean {
        var succeed = false
        playerInfoMap.computeIfPresent(name) { _, v ->
            if (v.score < 240) return@computeIfPresent v
            succeed = true
            v.copy(title = title)
        }
        if (succeed) pool.trySend(::savePlayerInfo)
        return succeed
    }

    /**
     * @return Pair(score的新值, score的变化量)
     */
    fun updateScore(player: Player, score: Int, save: Boolean): Pair<Int, Int> {
        var newScore = 0
        var delta = 0
        if (player is HumanPlayer) {
            playerInfoMap.computeIfPresent(player.playerName) { _, v ->
                newScore = v.score addScore score
                delta = newScore - v.score
                v.copy(score = newScore)
            }
        } else {
            robotInfoMap.compute(player.playerName) { _, v ->
                newScore = (v?.score ?: 0) addScore score
                delta = newScore - (v?.score ?: 0)
                v?.copy(score = newScore) ?: RobotInfo(player.playerName, newScore)
            }
        }
        if (save) pool.trySend(::savePlayerInfo)
        return newScore to delta
    }

    fun calculateRankList() {
        val now = System.currentTimeMillis()
        val l1 = playerInfoMap.map { (_, v) ->
            if (v.score <= 0) return@map v
            val days = ((now - v.lastTime) / (24 * 3600000L)).toInt()
            val decay = days / 7 * 20
            v.copy(score = (v.score - decay).coerceAtLeast(0))
        }.filter { it.score > 0 }.sortedWith { a, b ->
            if (a.score > b.score) -1
            else if (a.score < b.score) 1
            else if (a.lastTime > b.lastTime) -1
            else if (a.lastTime < b.lastTime) 1
            else if (a.gameCount > b.gameCount) -1
            else if (a.gameCount < b.gameCount) 1
            else b.winCount.compareTo(a.winCount)
        }

        fun makeRankList(count: Int): String {
            val l = l1.take(count)
            var i = 0
            return l.joinToString(separator = "\n") {
                val name = it.name.replace("\"", "\\\"")
                val rank = ScoreFactory.getRankNameByScore(it.score)
                "第${++i}名：$name·$rank·${it.score}"
            }
        }

        rankListImage.set(Image.genRankListImage(l1.take(50)))
        rankList25.set(makeRankList(25))
    }

    fun resetPassword(name: String): Boolean {
        if (playerInfoMap.computeIfPresent(name) { _, v -> v.copy(password = "") } != null) {
            pool.trySend(::savePlayerInfo)
            return true
        }
        return false
    }

    fun getPlayerGameCount(name: String): PlayerGameCount {
        val playerInfo = playerInfoMap[name] ?: return PlayerGameCount(0, 0)
        return PlayerGameCount(playerInfo.winCount, playerInfo.gameCount)
    }

    /**
     * 重置赛季
     */
    fun resetSeason() {
        playerInfoMap.keys.forEach {
            playerInfoMap.computeIfPresent(it) { _, v -> v.copy(winCount = 0, gameCount = 0, score = v.score / 2) }
        }
        pool.trySend(::savePlayerInfo)
        calculateRankList()
    }

    val totalPlayerGameCount: PlayerGameCount
        get() = PlayerGameCount(totalWinCount.get(), totalGameCount.get())

    private fun savePlayerInfo() {
        val sb = StringBuilder()
        for ((_, info) in playerInfoMap) {
            sb.append(info.winCount).append(',')
            sb.append(info.gameCount).append(',')
            sb.append(info.name).append(',')
            sb.append(info.score).append(',')
            sb.append(info.password).append(',')
            sb.append(info.forbidUntil).append(',')
            sb.append(info.title).append(',')
            sb.append(info.lastTime).append(',')
            sb.append(info.energy).append('\n')
        }
        writeFile("playerInfo.csv", sb.toString().toByteArray())
        sb.clear()
        for ((_, info) in robotInfoMap) {
            sb.append(info.score).append(',')
            sb.append(info.name).append('\n')
        }
        writeFile("robotInfo.csv", sb.toString().toByteArray())
    }

    private fun saveTrials() {
        val sb = StringBuilder()
        for ((key, value) in trialStartTime) {
            sb.append(value).append(',')
            sb.append(key).append('\n')
        }
        writeFile("trial.csv", sb.toString().toByteArray())
    }

    @Throws(IOException::class)
    fun load() {
        var winCount = 0
        var gameCount = 0
        try {
            BufferedReader(InputStreamReader(FileInputStream("playerInfo.csv"))).use { reader ->
                var line: String
                while (true) {
                    line = reader.readLine() ?: break
                    val a = line.split(",".toRegex(), limit = 9)
                    val pwd = a[4]
                    val score = if (a[3].length < 6) a[3].toInt() else 0 // 以前这个位置是deviceId
                    val name = a[2]
                    val win = a[0].toInt()
                    val game = a[1].toInt()
                    val forbid = a.getOrNull(5)?.toLong() ?: 0
                    val title = a.getOrNull(6) ?: ""
                    val lt = a.getOrNull(7)?.toLong() ?: 0
                    val energy = a.getOrNull(8)?.toInt() ?: 0
                    if (playerInfoMap.put(name, PlayerInfo(name, score, pwd, win, game, forbid, title, lt, energy)) != null)
                        throw RuntimeException("数据错误，有重复的玩家name")
                    winCount += win
                    gameCount += game
                }
            }
        } catch (ignored: FileNotFoundException) {
        }
        try {
            BufferedReader(InputStreamReader(FileInputStream("robotInfo.csv"))).use { reader ->
                var line: String
                while (true) {
                    line = reader.readLine() ?: break
                    val a = line.split(",".toRegex(), limit = 2)
                    val score = a[0].toInt()
                    val name = a[1]
                    if (robotInfoMap.put(name, RobotInfo(name, score)) != null)
                        throw RuntimeException("数据错误，有重复的机器人name")
                }
            }
        } catch (ignored: FileNotFoundException) {
        }
        totalWinCount.set(winCount)
        totalGameCount.set(gameCount)
        try {
            BufferedReader(InputStreamReader(FileInputStream("trial.csv"))).use { reader ->
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line == null) break
                    val a = line!!.split(",".toRegex(), limit = 2)
                    trialStartTime[a[1]] = a[0].toLong()
                }
            }
        } catch (ignored: FileNotFoundException) {
        }
        calculateRankList()
    }

    fun getTrialStartTime(playerName: String): Long {
        return trialStartTime.getOrDefault(playerName, 0L)
    }

    fun setTrialStartTime(playerName: String, time: Long) {
        pool.trySend {
            try {
                trialStartTime[playerName] = time
                saveTrials()
            } catch (e: Exception) {
                logger.error("execute task failed", e)
            }
        }
    }

    fun displayRecordList(player: HumanPlayer) {
        pool.trySend {
            val dir = File("records")
            val files = dir.list()
            player.send(getRecordListToc {
                if (files != null) {
                    files.sort()
                    var lastPrefix: String? = null
                    var j = 0
                    for (i in files.indices.reversed()) {
                        if (files[i].length < 19) continue
                        if (lastPrefix == null || !files[i].startsWith(lastPrefix)) {
                            if (++j > Config.RecordListSize) break
                            lastPrefix = files[i].substring(0, 19)
                        }
                        records.add(files[i])
                    }
                }
            })
        }
    }

    class Record(
        val role: role,
        val isWinner: Boolean,
        val identity: color,
        val task: secret_task,
        val totalPlayerCount: Int
    )

    class PlayerGameResult(val playerName: String, val isWin: Boolean)

    data class PlayerGameCount(val winCount: Int, val gameCount: Int) {
        fun random(): PlayerGameCount {
            val i = Random.nextInt(20)
            return PlayerGameCount(winCount * i / 100, gameCount * i / 100)
        }

        fun inc(isWinner: Boolean) = PlayerGameCount(winCount + if (isWinner) 1 else 0, gameCount + 1)

        val rate get() = if (gameCount == 0) 0.0 else winCount * 100.0 / gameCount
    }

    data class PlayerInfo(
        val name: String,
        val score: Int,
        val password: String,
        val winCount: Int,
        val gameCount: Int,
        val forbidUntil: Long,
        val title: String,
        val lastTime: Long,
        val energy: Int,
    ) {
        val scoreWithDecay: Int
            get() {
                val days = ((System.currentTimeMillis() - lastTime) / (24 * 3600000L)).toInt()
                val decay = days / 7 * 20
                return (score - decay).coerceAtLeast(0)
            }
    }

    data class RobotInfo(
        val name: String,
        val score: Int,
    )

    private fun writeFile(fileName: String, buf: ByteArray, append: Boolean = false) {
        try {
            FileOutputStream(fileName, append).use { fileOutputStream -> fileOutputStream.write(buf) }
        } catch (e: IOException) {
            logger.error("write file failed", e)
        }
    }

    private val hexDigests =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

    @Throws(NoSuchAlgorithmException::class)
    private fun md5(s: String): String {
        try {
            val `in` = s.toByteArray(StandardCharsets.UTF_8)
            val messageDigest = MessageDigest.getInstance("md5")
            messageDigest.update(`in`)
            // 获得密文
            val md = messageDigest.digest()
            // 将密文转换成16进制字符串形式
            val j = md.size
            val str = CharArray(j * 2)
            var k = 0
            for (b in md) {
                str[k++] = hexDigests[b.toInt() ushr 4 and 0xf] // 高4位
                str[k++] = hexDigests[b.toInt() and 0xf] // 低4位
            }
            return String(str)
        } catch (e: NoSuchAlgorithmException) {
            logger.warn("calculate md5 failed: ", e)
            return s
        }
    }
}
