package com.rinha.fraud.vec;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TopKTest {
    @Test
    void picksFiveSmallestAndCountsFrauds() {
        TopK t = new TopK();
        t.reset();
        int[] dist =   {50, 10, 40, 5, 30, 20, 60, 15};
        byte[] lab =   {1,  0,  1,  1, 0,  1,  1,  0};
        for (int i = 0; i < dist.length; i++) t.offer(dist[i], i);
        // smallest 5: 5(i3,f1),10(i1,f0),15(i7,f0),20(i5,f1),30(i4,f0) -> 2 frauds
        assertEquals(2, t.countFrauds(lab));
    }

    @Test
    void fewerThanFive() {
        TopK t = new TopK();
        t.reset();
        t.offer(3, 0); t.offer(1, 1);
        byte[] lab = {1, 1};
        assertEquals(2, t.countFrauds(lab));
    }
}
