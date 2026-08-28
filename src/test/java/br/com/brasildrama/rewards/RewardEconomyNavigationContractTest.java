package br.com.brasildrama.rewards;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RewardEconomyNavigationContractTest {
    @Test
    void rewardsTabsExposeHorizontalSwipeContract() {
        assertThat(RewardEconomyModel.navigationMode()).isEqualTo("HORIZONTAL_SWIPE");
        assertThat(RewardEconomyModel.forwardGesture()).isEqualTo("SWIPE_RIGHT");
        assertThat(RewardEconomyModel.backGesture()).isEqualTo("SWIPE_LEFT");
        assertThat(RewardEconomyModel.tabTapEnabled()).isTrue();
        assertThat(RewardEconomyModel.orderedTracks())
            .containsExactly(RewardEconomyModel.Track.COINS, RewardEconomyModel.Track.VIP);
    }
}
