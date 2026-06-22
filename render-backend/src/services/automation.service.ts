import { getAutomationRules, logAudit } from '../db/supabase'
import { sendYkpPacket } from '../router/route-engine'
import { RouteType, QoS } from '../packet/constants'

export async function runAutomationEngine(
  triggerDeviceId: string,
  triggerServiceId: number,
  triggerActionId: number,
  payload: any
): Promise<void> {
  try {
    const rules = await getAutomationRules()
    
    for (const rule of rules) {
      if (
        rule.trigger_device === triggerDeviceId &&
        rule.trigger_service === triggerServiceId &&
        rule.trigger_action === triggerActionId
      ) {
        // Evaluate conditions based on rule name (supporting standard demo conditions)
        let conditionMet = false
        
        if (rule.rule_name.includes('Motion')) {
          if (payload.motion === true) conditionMet = true
        } else if (rule.rule_name.includes('Temp>30')) {
          if (payload.temperature !== undefined && payload.temperature > 30) conditionMet = true
        } else if (rule.rule_name.includes('Temp<25')) {
          if (payload.temperature !== undefined && payload.temperature < 25) conditionMet = true
        } else {
          // Default fallback
          conditionMet = true
        }
        
        if (conditionMet) {
          console.log(`[automation] Rule "${rule.rule_name}" triggered! Executing target command on ${rule.target_device}...`)
          
          const targetDevice = rule.target_device
          const targetService = rule.target_service
          const targetAction = rule.target_action
          
          const sent = sendYkpPacket(targetDevice, {
            packetId:  Math.floor(Math.random() * 0xFFFFFF),
            sessionId: 0,
            sourceId:  'SERVER',
            destId:    targetDevice,
            routeType: RouteType.DIRECT,
            serviceId: targetService,
            actionId:  targetAction,
            qos:       QoS.QOS_2,
          })
          
          await logAudit(triggerDeviceId, 'AUTOMATION_TRIGGERED', {
            rule_id: rule.rule_id,
            rule_name: rule.rule_name,
            target_device: targetDevice,
            command_sent: sent
          })
        }
      }
    }
  } catch (err) {
    console.error('[automation] Error running rules:', err)
  }
}
