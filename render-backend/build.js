const { execSync } = require('child_process');
const path = require('path');

try {
  console.log('🚀 Building backend (tsc)...');
  execSync('npx tsc', { stdio: 'inherit' });

  console.log('\n📦 Installing web-dashboard dependencies...');
  execSync('npm install', { cwd: path.join(__dirname, '../web-dashboard'), stdio: 'inherit' });

  console.log('\n✨ Building web-dashboard (vite)...');
  execSync('npm run build', { cwd: path.join(__dirname, '../web-dashboard'), stdio: 'inherit' });

  console.log('\n🎉 Build completed successfully!');
} catch (error) {
  console.error('\n❌ Build failed:', error.message);
  process.exit(1);
}
