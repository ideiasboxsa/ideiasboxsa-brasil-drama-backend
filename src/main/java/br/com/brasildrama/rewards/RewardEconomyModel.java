package br.com.brasildrama.rewards;

import java.util.List;

/**
 * Product contract for the two independent monetization tracks.
 * COINS unlock eligible content and may be earned through rewarded ads when enabled.
 * VIP gamifies subscription benefits and never substitutes the coin ledger.
 */
final class RewardEconomyModel {
    private RewardEconomyModel() {}

    enum Track { COINS, VIP }
    enum CoinEarningChannel { MISSIONS, LOGIN_REWARD, REWARDED_AD }

    static List<Track> orderedTracks() {
        return List.of(Track.COINS, Track.VIP);
    }

    static String navigationMode() {
        return "HORIZONTAL_SWIPE";
    }

    static boolean tabTapEnabled() {
        return true;
    }

    static List<CoinEarningChannel> coinChannels(boolean rewardedAdsEnabled) {
        return rewardedAdsEnabled
            ? List.of(CoinEarningChannel.MISSIONS, CoinEarningChannel.LOGIN_REWARD, CoinEarningChannel.REWARDED_AD)
            : List.of(CoinEarningChannel.MISSIONS, CoinEarningChannel.LOGIN_REWARD);
    }

    static boolean vipPointsCanUnlockPaidEpisode() {
        return false;
    }
}
