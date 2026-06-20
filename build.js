const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

try {
  console.log('Compiling Tailwind CSS...');
  const tailwindBin = path.resolve(__dirname, 'node_modules', '.bin', 'tailwindcss');
  
  if (fs.existsSync(tailwindBin)) {
    console.log(`Using local binary at: ${tailwindBin}`);
    execSync(`"${tailwindBin}" -i ./docs/tailwind-input.css -o ./docs/style.css --minify`, { stdio: 'inherit' });
  } else {
    console.log('Local binary not found, falling back to npx @tailwindcss/cli');
    execSync('npx @tailwindcss/cli -i ./docs/tailwind-input.css -o ./docs/style.css --minify', { stdio: 'inherit' });
  }
  
  console.log('Copying Alpine.js...');
  const alpineSrc = path.resolve(__dirname, 'node_modules', 'alpinejs', 'dist', 'cdn.min.js');
  const alpineDest = path.resolve(__dirname, 'docs', 'alpine.js');
  fs.copyFileSync(alpineSrc, alpineDest);
  
  console.log('Build completed successfully!');
} catch (error) {
  console.error('Build failed:', error);
  process.exit(1);
}
