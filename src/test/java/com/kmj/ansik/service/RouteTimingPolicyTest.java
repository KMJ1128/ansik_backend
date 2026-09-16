package com.kmj.ansik.service;

import com.kmj.ansik.dto.AiCourseDto.CourseStop;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class RouteTimingPolicyTest {
    private CourseStop stop(String id, double lat, double lon, String category) {
        return new CourseStop(id, id, "", category, "", lat, lon, "", "", "", "");
    }
    @Test void handlesZeroOneAndTwoStops() {
        assertThat(RouteTimingPolicy.estimate(List.of())).isEmpty();
        var a = stop("a",37.57,126.97,"ATTRACTION");
        assertThat(RouteTimingPolicy.estimate(List.of(a)).get(0).recommendedTime()).isEqualTo("09:00");
        var two = RouteTimingPolicy.estimate(List.of(a,stop("b",37.58,126.98,"RESTAURANT")));
        assertThat(two).hasSize(2);
        assertThat(two.get(1).estimatedTransferMinutes()).isPositive();
        assertThat(two.get(1).suggestedStayMinutes()).isEqualTo(60);
    }
    @Test void longerDistanceNeedsMoreTimeAndLongTripWarning() {
        var a=stop("a",37.57,126.97,"ATTRACTION");
        var near=RouteTimingPolicy.estimate(List.of(a,stop("b",37.58,126.98,"ATTRACTION"))).get(1);
        var far=RouteTimingPolicy.estimate(List.of(a,stop("c",35.18,129.07,"ATTRACTION"))).get(1);
        assertThat(far.estimatedTransferMinutes()).isGreaterThan(near.estimatedTransferMinutes());
        assertThat(far.timingNeedsReview()).isTrue();
        assertThat(far.timingEstimated()).isTrue();
    }
    @Test void invalidCoordinatesAreNotPresentedAsKnownTravelTime() {
        var stops=RouteTimingPolicy.estimate(List.of(stop("a",0,0,"ATTRACTION"),stop("b",37.57,126.97,"ATTRACTION")));
        assertThat(stops).allMatch(CourseStop::timingNeedsReview);
        assertThat(stops.get(1).estimatedTransferMinutes()).isEqualTo(-1);
    }
    @Test void lateFinishIsFlagged() {
        var stops=IntStream.range(0,8).mapToObj(i->stop(""+i,37.57+i*.01,126.97,"ATTRACTION")).toList();
        assertThat(RouteTimingPolicy.estimate(stops).get(7).timingNeedsReview()).isTrue();
    }
    @Test void optimizationKeepsFirstAndRestaurantSlotAndAllPlaces() {
        var a=stop("a",37.5,127,"ATTRACTION");
        var b=stop("b",37.8,127,"ATTRACTION");
        var c=stop("c",37.6,127,"RESTAURANT");
        var d=stop("d",37.51,127,"ATTRACTION");
        var ordered=RouteTimingPolicy.order(List.of(a,b,c,d));
        assertThat(ordered).containsExactly(a,d,c,b);
    }
    @Test void invalidRouteKeepsOriginalOrder() {
        var stops=List.of(stop("a",0,0,"ATTRACTION"),stop("b",37.5,127,"ATTRACTION"),stop("c",37.6,127,"ATTRACTION"));
        assertThat(RouteTimingPolicy.order(stops)).containsExactlyElementsOf(stops);
    }
}
