package co.bancopopular.garantias360.comun;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.*;

/**
 * Ejecuta trabajo derivado (p. ej. recálculo de cobertura) después del commit, en un hilo limpio
 * con el mismo usuario y Correlation ID. Espera el resultado para que la respuesta de la API ya
 * refleje la cobertura nueva; si tarda más del límite, sigue en segundo plano.
 */
@Component
public class TareasPosteriores {

    private static final Logger log = LoggerFactory.getLogger(TareasPosteriores.class);
    private static final long ESPERA_SEGUNDOS = 30;

    private final ExecutorService ejecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "g360-posterior");
        t.setDaemon(true);
        return t;
    });

    public void despuesDelCommit(Runnable tarea) {
        String correlation = Contexto.correlationId();
        Runnable envuelta = new DelegatingSecurityContextRunnable(() -> Contexto.conCorrelation(correlation, tarea));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ejecutar(envuelta);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ejecutar(envuelta);
            }
        });
    }

    /**
     * Trabajo largo (p. ej. procesar una carga masiva) que corre después del commit sin bloquear la
     * respuesta, con el mismo usuario y Correlation ID.
     */
    public void enSegundoPlano(Runnable tarea) {
        String correlation = Contexto.correlationId();
        Runnable envuelta = new DelegatingSecurityContextRunnable(() -> Contexto.conCorrelation(correlation, () -> {
            try {
                tarea.run();
            } catch (RuntimeException e) {
                log.error("Falló una tarea en segundo plano", e);
            }
        }));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ejecutor.submit(envuelta);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ejecutor.submit(envuelta);
            }
        });
    }

    private void ejecutar(Runnable tarea) {
        Future<?> f = ejecutor.submit(tarea);
        try {
            f.get(ESPERA_SEGUNDOS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("La tarea posterior sigue en ejecución en segundo plano");
        } catch (ExecutionException e) {
            log.error("Falló una tarea posterior al commit", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void cerrar() {
        ejecutor.shutdown();
    }
}
