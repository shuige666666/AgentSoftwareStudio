const canvas = document.getElementById('gameCanvas') || document.getElementById('game-canvas');
if (!canvas) {
    console.error('Canvas element not found. Expected id "gameCanvas" or "game-canvas"');
}
const ctx = canvas ? canvas.getContext('2d') : null;

// 游戏配置
const gridSize = 20;
const gridWidth = canvas.width / gridSize;
const gridHeight = canvas.height / gridSize;

// 游戏状态
let snake = [];
let food = {};
let direction = 'right';
let nextDirection = 'right';
let score = 0;
let highScore = localStorage.getItem('highScore') || localStorage.getItem('snakeHighScore') || 0;
let gameOver = false;
let gameRunning = false;

// 初始化游戏
function initGame() {
    // 初始化蛇的位置（居中）
    snake = [
        {x: Math.floor(gridWidth / 2), y: Math.floor(gridHeight / 2)},
        {x: Math.floor(gridWidth / 2) - 1, y: Math.floor(gridHeight / 2)},
        {x: Math.floor(gridWidth / 2) - 2, y: Math.floor(gridHeight / 2)}
    ];
    
    generateFood();
    direction = 'right';
    nextDirection = 'right';
    score = 0;
    gameOver = false;
    gameRunning = true;
    
    updateScoreDisplay();
    drawGame();

    // ensure canvas is focusable and focused so keyboard events go to the game
    if (canvas) {
        canvas.tabIndex = canvas.tabIndex || 0;
        canvas.focus();
        canvas.addEventListener('click', () => canvas.focus());
    }
}

// 生成食物
function generateFood() {
    let newFood;
    let overlapping;
    
    do {
        overlapping = false;
        newFood = {
            x: Math.floor(Math.random() * gridWidth),
            y: Math.floor(Math.random() * gridHeight)
        };
        
        // 检查食物是否与蛇身重叠
        for (let segment of snake) {
            if (segment.x === newFood.x && segment.y === newFood.y) {
                overlapping = true;
                break;
            }
        }
    } while (overlapping);
    
    food = newFood;
}

// 绘制游戏
function drawGame() {
    // 清空画布
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    
    if (gameOver) {
        // 显示游戏结束信息
        ctx.fillStyle = '#fff';
        ctx.font = '24px Arial';
        ctx.textAlign = 'center';
        ctx.fillText('Game Over!', canvas.width / 2, canvas.height / 2);
        ctx.font = '16px Arial';
        ctx.fillText(`Final Score: ${score}`, canvas.width / 2, canvas.height / 2 + 30);
        ctx.fillText('Press SPACE to restart', canvas.width / 2, canvas.height / 2 + 60);
        return;
    }
    
    // 绘制蛇
    ctx.fillStyle = '#0f0';
    for (let i = 0; i < snake.length; i++) {
        const segment = snake[i];
        ctx.fillRect(segment.x * gridSize, segment.y * gridSize, gridSize, gridSize);
        
        // 蛇头用不同颜色
        if (i === 0) {
            ctx.fillStyle = '#0a0';
            ctx.fillRect(segment.x * gridSize, segment.y * gridSize, gridSize, gridSize);
            ctx.fillStyle = '#0f0';
        }
    }
    
    // 绘制食物
    ctx.fillStyle = '#f00';
    ctx.fillRect(food.x * gridSize, food.y * gridSize, gridSize, gridSize);
}

// 处理键盘按键
function handleKeyPress(event) {
    switch(event.key) {
        case 'ArrowUp':
            if (direction !== 'down') nextDirection = 'up';
            break;
        case 'ArrowDown':
            if (direction !== 'up') nextDirection = 'down';
            break;
        case 'ArrowLeft':
            if (direction !== 'right') nextDirection = 'left';
            break;
        case 'ArrowRight':
            if (direction !== 'left') nextDirection = 'right';
            break;
        case ' ':
            if (gameOver) {
                initGame();
            }
            break;
    }
}

// 更新游戏逻辑
function updateGame() {
    if (!gameRunning || gameOver) return;
    
    // 更新方向
    direction = nextDirection;
    
    // 计算新的头部位置
    const head = {...snake[0]};
    
    switch(direction) {
        case 'up':
            head.y -= 1;
            break;
        case 'down':
            head.y += 1;
            break;
        case 'left':
            head.x -= 1;
            break;
        case 'right':
            head.x += 1;
            break;
    }
    
    // 检查碰撞边界
    if (head.x < 0 || head.x >= gridWidth || head.y < 0 || head.y >= gridHeight) {
        gameOver = true;
        endGame();
        return;
    }
    
    // 检查碰撞自己
    for (let i = 0; i < snake.length; i++) {
        if (snake[i].x === head.x && snake[i].y === head.y) {
            gameOver = true;
            endGame();
            return;
        }
    }
    
    // 添加新的头部
    snake.unshift(head);
    
    // 检查是否吃到食物
    if (head.x === food.x && head.y === food.y) {
        // 增加分数
        score += 10;
        updateScoreDisplay();
        
        // 生成新食物
        generateFood();
    } else {
        // 移除尾部
        snake.pop();
    }
    
    // 发送方向更新到后端
    sendDirectionUpdate(direction);
    
    // 重绘游戏
    drawGame();
}

// 发送方向更新到后端
function sendDirectionUpdate(dir) {
    // 这里可以发送AJAX请求到后端API
    // 示例：
    // fetch('/api/game/direction', {
    //     method: 'POST',
    //     headers: {
    //         'Content-Type': 'application/json',
    //     },
    //     body: JSON.stringify({ direction: dir })
    // })
    // .catch(error => console.error('Error sending direction:', error));
}

// 更新分数显示 - be tolerant about ID variants used in HTML files
function updateScoreDisplay() {
    const curEl = document.getElementById('currentScore') || document.getElementById('current-score');
    const highEl = document.getElementById('highScore') || document.getElementById('high-score');
    if (curEl) curEl.textContent = `Score: ${score}`;
    if (highEl) highEl.textContent = `High Score: ${highScore}`;

    // 更新本地存储中的最高分
    if (score > highScore) {
        highScore = score;
        // store under both keys for compatibility
        localStorage.setItem('highScore', highScore);
        localStorage.setItem('snakeHighScore', highScore);
        if (highEl) highEl.textContent = `High Score: ${highScore}`;
    }
}

// 结束游戏
function endGame() {
    gameRunning = false;
    drawGame();
}

// 键盘事件监听：阻止默认滚动行为并调用处理器
window.addEventListener('keydown', (e) => {
    const keysToPrevent = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', ' ', 'Space', 'Spacebar'];
    if (keysToPrevent.includes(e.key)) {
        e.preventDefault();
    }
    handleKeyPress(e);
});

// 游戏循环
setInterval(updateGame, 150); // 每150毫秒更新一次游戏状态

// 初始化游戏
initGame();
// ensure score display is updated once at load
updateScoreDisplay();
// give canvas focus if possible
if (canvas) {
    canvas.tabIndex = canvas.tabIndex || 0;
    canvas.addEventListener('click', () => canvas.focus());
}
