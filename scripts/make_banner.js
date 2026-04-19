const sharp = require('sharp');
const fs = require('fs').promises;
const path = require('path');

async function makeBanner() {
    const bannerWidth = 1200;
    const bannerHeight = 500;
    
    // Resize function
    async function getBase64Image(filePath) {
        const buffer = await sharp(path.join(__dirname, '..', filePath))
            .resize({ width: 400 }) // Higher quality for the banner
            .jpeg({ quality: 90 })
            .toBuffer();
        return `data:image/jpeg;base64,${buffer.toString('base64')}`;
    }

    try {
        const imgEmpty = await getBase64Image('docs/screenshot_empty.jpg');
        const imgPlayer = await getBase64Image('docs/screenshot_player.jpg');
        const imgExport = await getBase64Image('docs/screenshot_export.jpg');
        const iconSvg = await fs.readFile(path.join(__dirname, '..', 'docs', 'icon.svg'), 'utf8');
        
        // Extract the core of the icon SVG to embed
        const iconContent = iconSvg.replace(/<svg[^>]*>/, '').replace(/<\/svg>/, '');

        const svg = `
<svg viewBox="0 0 ${bannerWidth} ${bannerHeight}" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
    <defs>
        <linearGradient id="bgSky" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stop-color="#F0F9FF" /> 
            <stop offset="40%" stop-color="#E0F2FE" />
            <stop offset="100%" stop-color="#BAE6FD" /> 
        </linearGradient>
        <linearGradient id="premiumText" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stop-color="#075985" />
            <stop offset="100%" stop-color="#0369A1" />
        </linearGradient>
        <filter id="mainShadow" x="-20%" y="-20%" width="140%" height="140%">
            <feDropShadow dx="0" dy="24" stdDeviation="32" flood-color="#075985" flood-opacity="0.15"/>
        </filter>
        <filter id="iconShadow" x="-50%" y="-50%" width="200%" height="200%">
            <feDropShadow dx="0" dy="12" stdDeviation="16" flood-color="#0284C7" flood-opacity="0.3"/>
        </filter>
        <filter id="softBlur" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="50" />
        </filter>
        <clipPath id="phoneClip">
            <rect x="0" y="0" width="240" height="500" rx="36" />
        </clipPath>
        
        <!-- Reuse icon gradients -->
        ${iconSvg.match(/<defs>([\s\S]*?)<\/defs>/)[1]}
    </defs>
    
    <!-- Background Base -->
    <rect width="${bannerWidth}" height="${bannerHeight}" fill="url(#bgSky)" />
    
    <!-- Decorative Blurs -->
    <circle cx="100" cy="100" r="200" fill="#FFFFFF" opacity="0.7" filter="url(#softBlur)" />
    <circle cx="1100" cy="400" r="250" fill="#38BDF8" opacity="0.15" filter="url(#softBlur)" />
    <circle cx="600" cy="250" r="150" fill="#BAE6FD" opacity="0.2" filter="url(#softBlur)" />

    <!-- Content Group -->
    <g transform="translate(60, 140)">
        <!-- App Icon with Shadow -->
        <g transform="scale(0.25) translate(0, -100)" filter="url(#iconShadow)">
            ${iconContent}
        </g>
        
        <!-- Text Info -->
        <g transform="translate(150, 0)">
            <text x="0" y="40" font-family="'Inter', -apple-system, sans-serif" font-weight="900" font-size="72" fill="url(#premiumText)" letter-spacing="-3">FrameEcho</text>
            <text x="2" y="95" font-family="'Inter', -apple-system, sans-serif" font-weight="600" font-size="28" fill="#0369A1" opacity="0.9">定格你所爱的每一帧</text>
            <text x="2" y="130" font-family="'Inter', -apple-system, sans-serif" font-weight="400" font-size="20" fill="#0284C7" opacity="0.7">High-Performance Frame Capture</text>
            
            <!-- Features Badges -->
            <g transform="translate(0, 170)">
                <rect width="100" height="28" rx="14" fill="#0284C7" />
                <text x="50" y="19" font-family="sans-serif" font-size="12" font-weight="800" fill="#FFFFFF" text-anchor="middle">HDR AWARE</text>
                
                <rect x="110" width="120" height="28" rx="14" fill="#E0F2FE" stroke="#BAE6FD" stroke-width="1" />
                <text x="170" y="19" font-family="sans-serif" font-size="12" font-weight="600" fill="#0369A1" text-anchor="middle">MOTION PHOTO</text>
            </g>
        </g>
    </g>

    <!-- Mockups Visualization -->
    <g transform="translate(760, 50)">
        <!-- Export Screen (Right, Back) -->
        <g transform="translate(220, 40) rotate(12)" filter="url(#mainShadow)">
            <rect width="240" height="500" rx="36" fill="#F8FAFC" stroke="#E2E8F0" stroke-width="2" />
            <image href="${imgExport}" width="240" height="518" clip-path="url(#phoneClip)" preserveAspectRatio="xMidYMid slice" opacity="0.9"/>
            <rect width="240" height="500" rx="36" fill="none" stroke="white" stroke-opacity="0.5" stroke-width="4" />
        </g>

        <!-- Empty Screen (Left, Back) -->
        <g transform="translate(-100, 60) rotate(-8)" filter="url(#mainShadow)">
            <rect width="240" height="500" rx="36" fill="#F8FAFC" stroke="#E2E8F0" stroke-width="2" />
            <image href="${imgEmpty}" width="240" height="518" clip-path="url(#phoneClip)" preserveAspectRatio="xMidYMid slice" opacity="0.9"/>
            <rect width="240" height="500" rx="36" fill="none" stroke="white" stroke-opacity="0.5" stroke-width="4" />
        </g>

        <!-- Player Screen (Center, Front) -->
        <g transform="translate(60, 20) rotate(0)" filter="url(#mainShadow)">
            <rect width="240" height="500" rx="36" fill="#FFFFFF" stroke="#CBD5E1" stroke-width="1" />
            <image href="${imgPlayer}" width="240" height="518" clip-path="url(#phoneClip)" preserveAspectRatio="xMidYMid slice" />
            <!-- Realistic Border/Glass effect -->
            <rect width="240" height="500" rx="36" fill="none" stroke="url(#glassRim)" stroke-width="6" opacity="0.8" />
            <rect width="240" height="500" rx="36" fill="none" stroke="#000" stroke-opacity="0.05" stroke-width="1" />
        </g>
    </g>
</svg>`;

        await fs.writeFile(path.join(__dirname, '..', 'docs', 'banner.svg'), svg);
        console.log("Banner created successfully with premium design!");
    } catch (e) {
        console.error("Error creating banner:", e);
    }
}

makeBanner();
