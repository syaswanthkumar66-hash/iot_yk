/* ------------------------------------------------
   task_handles.h — Single source of truth for all
   FreeRTOS task handles. Define once in main.c,
   extern everywhere else. (Fix for flaw C3)
   ------------------------------------------------ */
#ifndef TASK_HANDLES_H
#define TASK_HANDLES_H

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

/* Defined once in main.c — extern here for all consumers */
extern TaskHandle_t g_conn_mgr_task_handle;

#endif /* TASK_HANDLES_H */
