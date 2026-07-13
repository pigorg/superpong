package com.alessandrognola.superpong

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.PlayGamesSdk

/**
 * Wraps Google Play Games Services leaderboards so "Top Score" can eventually show a
 * real cross-device ranking instead of just this device's local best.
 *
 * Not wired up yet - setup still needed before it does anything:
 *   1. Create the game in Play Console > Play Games Services > Setup, get an App ID.
 *   2. Put that App ID in res/values/games-ids.xml (game_services_app_id).
 *   3. Create a leaderboard in Play Console and put its ID in LEADERBOARD_ID below.
 * Until all three are done, every call here is a safe no-op and the game keeps using
 * the local high score only - that's expected, not a bug.
 */
object LeaderboardManager {
    private const val TAG = "LeaderboardManager"
    private const val LEADERBOARD_ID = "REPLACE_WITH_LEADERBOARD_ID"
    private const val RC_LEADERBOARD_UI = 9004

    @Volatile private var authenticated = false

    fun init(activity: Activity) {
        try {
            PlayGamesSdk.initialize(activity)
            PlayGames.getGamesSignInClient(activity).isAuthenticated()
                .addOnCompleteListener { task ->
                    authenticated = task.isSuccessful && task.result?.isAuthenticated == true
                }
        } catch (e: Exception) {
            Log.w(TAG, "Play Games Services unavailable: ${e.message}")
            authenticated = false
        }
    }

    fun submitScore(activity: Activity, score: Long) {
        if (!authenticated || LEADERBOARD_ID.startsWith("REPLACE")) return
        try {
            PlayGames.getLeaderboardsClient(activity).submitScore(LEADERBOARD_ID, score)
        } catch (e: Exception) {
            Log.w(TAG, "submitScore failed: ${e.message}")
        }
    }

    /** Tries to open Google's native leaderboard screen. Returns false when not configured/signed in yet. */
    fun tryOpenLeaderboardUi(activity: Activity): Boolean {
        if (!authenticated || LEADERBOARD_ID.startsWith("REPLACE")) return false
        return try {
            PlayGames.getLeaderboardsClient(activity)
                .getLeaderboardIntent(LEADERBOARD_ID)
                .addOnSuccessListener { intent: Intent -> activity.startActivityForResult(intent, RC_LEADERBOARD_UI) }
                .addOnFailureListener { e -> Log.w(TAG, "getLeaderboardIntent failed: ${e.message}") }
            true
        } catch (e: Exception) {
            Log.w(TAG, "tryOpenLeaderboardUi failed: ${e.message}")
            false
        }
    }
}
