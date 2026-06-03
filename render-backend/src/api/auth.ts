import { Router, Request, Response } from 'express'
import { supabase } from '../db/supabase'

const router = Router()

/** POST /api/auth/register — register a new user account */
router.post('/register', async (req: Request, res: Response) => {
  const { email, password } = req.body as { email?: string; password?: string }

  if (!email || !password) {
    return res.status(400).json({ error: 'Email and password are required' })
  }

  try {
    const { data, error } = await supabase.auth.signUp({
      email,
      password,
    })

    if (error) {
      return res.status(400).json({ error: error.message })
    }

    return res.json({
      success: true,
      message: 'Account registered successfully.',
      user: data.user,
    })
  } catch (err: any) {
    console.error('[auth register] error:', err.message || err)
    return res.status(500).json({ error: 'Internal Server Error during registration' })
  }
})

/** POST /api/auth/login — authenticate and return token */
router.post('/login', async (req: Request, res: Response) => {
  const { email, password } = req.body as { email?: string; password?: string }

  if (!email || !password) {
    return res.status(400).json({ error: 'Email and password are required' })
  }

  try {
    const { data, error } = await supabase.auth.signInWithPassword({
      email,
      password,
    })

    if (error) {
      return res.status(400).json({ error: error.message })
    }

    return res.json({
      success: true,
      user: data.user,
      session: data.session,
    })
  } catch (err: any) {
    console.error('[auth login] error:', err.message || err)
    return res.status(500).json({ error: 'Internal Server Error during login' })
  }
})

export default router
