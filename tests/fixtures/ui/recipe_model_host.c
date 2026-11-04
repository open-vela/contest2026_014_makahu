/* Fixture-only lock control around the actual production model. */
#include "buffer_a2ui_model.c"
void recipe_model_lock(void){pthread_mutex_lock(&g_model_lock);}
void recipe_model_unlock(void){pthread_mutex_unlock(&g_model_lock);}

void recipe_model_restart(void)
{
 pthread_mutex_lock(&g_model_lock);memset(g_surfaces,0,sizeof(g_surfaces));memset(g_closed_ids,0,sizeof(g_closed_ids));g_closed_count=g_closed_next=0;g_active_surface=-1;g_surface_sequence=g_task_revision=0;pthread_mutex_unlock(&g_model_lock);
}
