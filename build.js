const fs = require('fs');
const { execSync } = require('child_process');

try {
  console.log('Compiling Tailwind CSS...');
  execSync('npx tailwindcss -i ./docs/tailwind-input.css -o ./docs/style.css --minify', { stdio: 'inherit' });
  
  console.log('Copying Alpine.js...');
  fs.copyFileSync('node_modules/alpinejs/dist/cdn.min.js', 'docs/alpine.js');
  
  console.log('Build completed successfully!');
} catch (error) {
  console.error('Build failed:', error);
  process.exit(1);
}
