import { useState } from 'react'
import { Link } from 'react-router-dom'
import { register, isAuthenticated } from '../lib/api'
import { UserPlus, CheckCircle2, XCircle, Loader2, KeyRound, Mail } from 'lucide-react'

export default function Register() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState(null)
  const [error, setError] = useState(null)

  const handleRegister = async (e) => {
    e.preventDefault()
    setMessage(null)
    setError(null)

    // Validation
    if (!email || !password || !confirmPassword) {
      setError('Please fill in all fields.')
      return
    }

    if (password.length < 6) {
      setError('Password must be at least 6 characters long.')
      return
    }

    if (password !== confirmPassword) {
      setError('Passwords do not match.')
      return
    }

    setLoading(true)
    try {
      const data = await register(email, password)

      if (data?.success) {
        setMessage('Account registered successfully! You can now log into the YKP Mobile App.')
        // Reset form
        setEmail('')
        setPassword('')
        setConfirmPassword('')
      } else {
        throw new Error(data?.error || 'Registration failed')
      }
    } catch (err) {
      console.error('Registration error:', err)
      setError(err.message || 'Failed to register account.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      minHeight: '100vh',
      width: '100vw',
      padding: '20px',
      position: 'relative',
      overflow: 'hidden'
    }}>
      {/* Background glow effects to enhance aesthetics */}
      <div style={{
        position: 'absolute',
        top: '20%',
        left: '30%',
        width: '400px',
        height: '400px',
        background: 'radial-gradient(circle, var(--primary-glow) 0%, transparent 70%)',
        zIndex: 0,
        pointerEvents: 'none'
      }} />
      <div style={{
        position: 'absolute',
        bottom: '20%',
        right: '30%',
        width: '350px',
        height: '350px',
        background: 'radial-gradient(circle, var(--secondary-glow) 0%, transparent 70%)',
        zIndex: 0,
        pointerEvents: 'none'
      }} />

      <div className="card" style={{
        width: '100%',
        maxWidth: '500px',
        padding: '32px',
        zIndex: 1,
        boxShadow: '0 20px 40px rgba(0, 0, 0, 0.4), 0 0 0 1px var(--border)',
        background: 'rgba(13, 15, 42, 0.8)',
        backdropFilter: 'blur(20px)',
        position: 'relative',
        overflow: 'hidden'
      }}>
        
        {/* Glow header accent */}
        <div style={{
          position: 'absolute',
          top: 0, left: 0, right: 0,
          height: '4px',
          background: 'linear-gradient(90deg, var(--primary), var(--secondary))'
        }} />

        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '24px' }}>
          <div style={{
            width: '40px', height: '40px',
            borderRadius: '8px',
            background: 'var(--primary-dim)',
            display: 'flex', alignItems: 'center', justifySpaceAround: 'center', justifyContent: 'center',
            color: 'var(--primary)'
          }}>
            <UserPlus size={20} />
          </div>
          <div>
            <h3 style={{ fontSize: '18px', fontWeight: '700' }}>Register Mobile App Account</h3>
            <p style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>
              Create a new user account in Supabase for authentication on iOS/Android
            </p>
          </div>
        </div>

        {/* Success Alert */}
        {message && (
          <div style={{
            display: 'flex', gap: '12px',
            background: 'var(--success-dim)',
            border: '1px solid rgba(0, 230, 118, 0.2)',
            borderRadius: 'var(--radius-sm)',
            padding: '12px 16px',
            marginBottom: '20px',
            color: 'var(--success)',
            fontSize: '14px'
          }}>
            <CheckCircle2 size={20} style={{ flexShrink: 0, marginTop: '2px' }} />
            <div>
              <strong style={{ display: 'block', fontWeight: '600', marginBottom: '2px' }}>Registration Success</strong>
              <span>{message}</span>
            </div>
          </div>
        )}

        {/* Error Alert */}
        {error && (
          <div style={{
            display: 'flex', gap: '12px',
            background: 'var(--danger-dim)',
            border: '1px solid rgba(255, 82, 82, 0.2)',
            borderRadius: 'var(--radius-sm)',
            padding: '12px 16px',
            marginBottom: '20px',
            color: 'var(--danger)',
            fontSize: '14px'
          }}>
            <XCircle size={20} style={{ flexShrink: 0, marginTop: '2px' }} />
            <div>
              <strong style={{ display: 'block', fontWeight: '600', marginBottom: '2px' }}>Registration Failed</strong>
              <span>{error}</span>
            </div>
          </div>
        )}

        <form onSubmit={handleRegister} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          
          {/* Email field */}
          <div className="form-group">
            <label style={{ display: 'block', fontSize: '12px', fontWeight: '600', color: 'var(--text-secondary)', marginBottom: '6px' }}>
              Email Address
            </label>
            <div style={{ position: 'relative' }}>
              <input
                type="email"
                placeholder="user@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={loading}
                style={{
                  width: '100%',
                  padding: '10px 12px 10px 38px',
                  borderRadius: 'var(--radius-sm)',
                  border: '1px solid var(--border)',
                  background: 'var(--bg-input)',
                  color: 'var(--text-primary)',
                  fontSize: '14px',
                  outline: 'none',
                  transition: 'var(--transition)'
                }}
                className="custom-input"
              />
              <Mail size={16} style={{
                position: 'absolute',
                left: '12px', top: '50%',
                transform: 'translateY(-50%)',
                color: 'var(--text-secondary)'
              }} />
            </div>
          </div>

          {/* Password field */}
          <div className="form-group">
            <label style={{ display: 'block', fontSize: '12px', fontWeight: '600', color: 'var(--text-secondary)', marginBottom: '6px' }}>
              Password
            </label>
            <div style={{ position: 'relative' }}>
              <input
                type="password"
                placeholder="Min. 6 characters"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
                style={{
                  width: '100%',
                  padding: '10px 12px 10px 38px',
                  borderRadius: 'var(--radius-sm)',
                  border: '1px solid var(--border)',
                  background: 'var(--bg-input)',
                  color: 'var(--text-primary)',
                  fontSize: '14px',
                  outline: 'none',
                  transition: 'var(--transition)'
                }}
                className="custom-input"
              />
              <KeyRound size={16} style={{
                position: 'absolute',
                left: '12px', top: '50%',
                transform: 'translateY(-50%)',
                color: 'var(--text-secondary)'
              }} />
            </div>
          </div>

          {/* Confirm Password field */}
          <div className="form-group">
            <label style={{ display: 'block', fontSize: '12px', fontWeight: '600', color: 'var(--text-secondary)', marginBottom: '6px' }}>
              Confirm Password
            </label>
            <div style={{ position: 'relative' }}>
              <input
                type="password"
                placeholder="Confirm password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                disabled={loading}
                style={{
                  width: '100%',
                  padding: '10px 12px 10px 38px',
                  borderRadius: 'var(--radius-sm)',
                  border: '1px solid var(--border)',
                  background: 'var(--bg-input)',
                  color: 'var(--text-primary)',
                  fontSize: '14px',
                  outline: 'none',
                  transition: 'var(--transition)'
                }}
                className="custom-input"
              />
              <KeyRound size={16} style={{
                position: 'absolute',
                left: '12px', top: '50%',
                transform: 'translateY(-50%)',
                color: 'var(--text-secondary)'
              }} />
            </div>
          </div>

          {/* Submit Button */}
          <button
            type="submit"
            className="btn primary"
            disabled={loading}
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
              padding: '12px',
              borderRadius: 'var(--radius-sm)',
              border: 'none',
              background: 'linear-gradient(135deg, var(--primary), var(--secondary))',
              color: '#fff',
              fontSize: '14px',
              fontWeight: '600',
              cursor: 'pointer',
              boxShadow: '0 4px 15px rgba(124, 111, 255, 0.25)',
              transition: 'var(--transition)',
              marginTop: '10px'
            }}
          >
            {loading ? (
              <>
                <Loader2 size={16} className="animate-spin" />
                Registering Account...
              </>
            ) : (
              <>
                <UserPlus size={16} />
                Register Account
              </>
            )}
          </button>
        </form>

        <div style={{ marginTop: '24px', paddingTop: '20px', borderTop: '1px solid var(--border)', fontSize: '12px', color: 'var(--text-secondary)', lineHeight: '1.5' }}>
          <strong>Note:</strong> Since Supabase serves as the single source of truth for both Web and Mobile apps, registering here creates a unified credential. Once registered, open the <strong>YKP Mobile App</strong> on your phone and log in using this email and password to start managing your provisioned switches.
        </div>

        <div style={{
          marginTop: '24px',
          paddingTop: '20px',
          borderTop: '1px solid var(--border)',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          fontSize: '12px',
          color: 'var(--text-secondary)'
        }}>
          <span>Already have an account?</span>
          <Link to={isAuthenticated() ? "/dashboard" : "/login"} style={{
            color: 'var(--secondary)',
            textDecoration: 'none',
            fontWeight: '600',
            transition: 'var(--transition)'
          }}>
            {isAuthenticated() ? "Back to Dashboard" : "Sign In"}
          </Link>
        </div>
      </div>
    </div>
  )
}
