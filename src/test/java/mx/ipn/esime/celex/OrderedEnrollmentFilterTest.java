package mx.ipn.esime.celex;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import mx.ipn.esime.celex.config.OrderedEnrollmentFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class OrderedEnrollmentFilterTest {
  @Test
  void arrivalOrderSurvivesContentionAndFailure() throws Exception {
    var filter = new OrderedEnrollmentFilter();
    var order = Collections.synchronizedList(new ArrayList<Integer>());
    var failures = Collections.synchronizedList(new ArrayList<Throwable>());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    java.util.function.IntFunction<Thread> thread =
        n ->
            new Thread(
                () -> {
                  try {
                    var request = new MockHttpServletRequest("POST", "/fila/folio" + n + "/grupo");
                    request.setServletPath("/fila/folio" + n + "/grupo");
                    filter.doFilter(
                        request,
                        new MockHttpServletResponse(),
                        (r, s) -> {
                          order.add(n);
                          if (n == 1) {
                            entered.countDown();
                            try {
                              release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                          }
                          if (n == 2) throw new java.io.IOException("fallo controlado");
                        });
                  } catch (java.io.IOException e) {
                    if (n != 2) failures.add(e);
                  } catch (Throwable e) {
                    failures.add(e);
                  }
                });
    Thread first = thread.apply(1), second = thread.apply(2), third = thread.apply(3);
    first.start();
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    second.start();
    awaitWaiting(second);
    third.start();
    awaitWaiting(third);
    assertEquals(List.of(1), order);
    release.countDown();
    for (var t : List.of(first, second, third)) {
      t.join(5000);
      assertFalse(t.isAlive());
    }
    assertEquals(List.of(1, 2, 3), order);
    assertTrue(failures.isEmpty());
  }

  private void awaitWaiting(Thread thread) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < end) Thread.sleep(1);
    assertEquals(Thread.State.WAITING, thread.getState());
  }
}
