package br.com.brasildrama.rewards;

import java.util.List;

/**
 * Product contract for the two independent monetization tracks.
 * COINS unlock eligible content and may be earned through rewarded ads when enabled.
 * VIP_POINTS gamify subscription/VIP benefits and never substitute the coin ledger.
 */
final class RewardEconomyModel {
    private RewardEconomyModel() {}

    enum Track { COINS, VIP }
    enum CoinEarningChannel { MISSIONS, LOGIN_REWARD, REWARDED_AD }

    static List<CoinEarningChannel> coinChannels(boolean rewardedAdsEnabled) {
        return rewardedAdsEnabled
            ? List.of(CoinEarningChannel.MISSIONS, CoinEarningChannel.LOGIN_REWARD, CoinEarningChannel.REWARDED_AD)
            : List.of(CoinEarningChannel.MISSIONS, CoinEarningChannel.LOGIN_REWARD);
    }

    static boolean vipPointsCanUnlockPaidEpisode() {
        return false;
    }
}
