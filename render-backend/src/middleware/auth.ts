import { Request, Response, NextFunction } from 'express'
import { supabase } from '../db/supabase'

export interface AuthenticatedRequest extends Request {
  user?: any
}

export async function authMiddleware(req: AuthenticatedRequest, res: Response, next: NextFunction) {
  const authHeader = req.headers.authorization

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'Authorization token missing or malformed' })
  }

  const token = authHeader.split(' ')[1]

  try {
    const { data: { user }, error } = await supabase.auth.getUser(token)

    if (error || !user) {
      return res.status(401).json({ error: 'Invalid or expired authorization token' })
    }

    // Attach the user object to the request
    req.user = user
    return next()
  } catch (err: any) {
    console.error('[auth middleware] error verifying token:', err.message || err)
    return res.status(500).json({ error: 'Internal Server Error during token verification' })
  }
}
