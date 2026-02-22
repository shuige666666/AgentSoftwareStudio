// 前端JavaScript逻辑，增强用户体验

// 更新单位选项的函数
document.addEventListener('DOMContentLoaded', function() {
    // 获取转换类型下拉框元素
    const conversionTypeSelect = document.getElementById('conversionType');
    
    if(conversionTypeSelect) {
        conversionTypeSelect.addEventListener('change', updateUnitOptions);
    }
});

function updateUnitOptions() {
    // 获取当前选中的转换类型
    const conversionType = document.getElementById('conversionType').value;
    
    // 获取源单位和目标单位下拉框
    const fromUnitSelect = document.getElementById('fromUnit');
    const toUnitSelect = document.getElementById('toUnit');
    
    // 清空现有的选项
    fromUnitSelect.innerHTML = '';
    toUnitSelect.innerHTML = '';
    
    // 根据转换类型设置不同的单位选项
    let units = [];
    
    switch(conversionType) {
        case 'length':
            units = [
                { value: 'meter', text: '米 (m)' },
                { value: 'kilometer', text: '千米 (km)' },
                { value: 'mile', text: '英里 (mi)' }
            ];
            break;
        case 'temperature':
            units = [
                { value: 'celsius', text: '摄氏度 (°C)' },
                { value: 'fahrenheit', text: '华氏度 (°F)' }
            ];
            break;
        case 'weight':
            units = [
                { value: 'gram', text: '克 (g)' },
                { value: 'kilogram', text: '千克 (kg)' },
                { value: 'pound', text: '磅 (lb)' }
            ];
            break;
        case 'volume':
            units = [
                { value: 'liter', text: '升 (L)' },
                { value: 'milliliter', text: '毫升 (mL)' },
                { value: 'gallon', text: '加仑 (gal)' }
            ];
            break;
        default:
            units = [];
    }
    
    // 为源单位和目标单位下拉框添加选项
    units.forEach(unit => {
        const fromOption = document.createElement('option');
        fromOption.value = unit.value;
        fromOption.textContent = unit.text;
        fromUnitSelect.appendChild(fromOption);
        
        const toOption = document.createElement('option');
        toOption.value = unit.value;
        toOption.textContent = unit.text;
        toUnitSelect.appendChild(toOption);
    });
    
    // 设置默认选中项
    if(units.length > 0) {
        fromUnitSelect.selectedIndex = 0;
        toUnitSelect.selectedIndex = 1 < units.length ? 1 : 0;
    }
}

// 验证输入的函数
function validateInput(inputId, allowDecimal = true, allowNegative = true) {
    const inputElement = document.getElementById(inputId);
    if (!inputElement) {
        console.error(`Input element with id '${inputId}' not found.`);
        return false;
    }
    
    const inputValue = inputElement.value.trim();
    
    // 检查是否为空
    if (inputValue === '') {
        showError(inputElement, '请输入数值');
        return false;
    }
    
    // 根据参数决定是否允许小数
    let numberRegex;
    if (allowDecimal) {
        numberRegex = allowNegative ? /^-?\d+(\.\d+)?$/ : /^\d+(\.\d+)?$/;
    } else {
        numberRegex = allowNegative ? /^-?\d+$/ : /^\d+$/;
    }
    
    // 验证格式
    if (!numberRegex.test(inputValue)) {
        showError(inputElement, '请输入有效的数值');
        return false;
    }
    
    // 转换为数字进行进一步验证
    const numberValue = parseFloat(inputValue);
    
    // 检查是否为有效数字
    if (isNaN(numberValue)) {
        showError(inputElement, '请输入有效的数值');
        return false;
    }
    
    // 如果不允许负数，检查数值是否为负
    if (!allowNegative && numberValue < 0) {
        showError(inputElement, '请输入非负数值');
        return false;
    }
    
    // 移除错误提示
    removeError(inputElement);
    
    return true;
}

// 显示错误信息的辅助函数
function showError(element, message) {
    // 移除之前的错误样式和消息
    removeError(element);
    
    // 添加错误边框样式
    element.classList.add('is-invalid');
    
    // 创建错误消息元素
    const errorDiv = document.createElement('div');
    errorDiv.className = 'invalid-feedback';
    errorDiv.textContent = message;
    
    // 将错误消息插入到输入框后面
    element.parentNode.insertBefore(errorDiv, element.nextSibling);
}

// 移除错误信息的辅助函数
function removeError(element) {
    // 移除错误边框样式
    element.classList.remove('is-invalid');
    
    // 查找并移除错误消息元素
    const nextSibling = element.nextSibling;
    if (nextSibling && nextSibling.classList.contains('invalid-feedback')) {
        nextSibling.remove();
    }
}

// 为计算表单添加实时验证
function addRealTimeValidation() {
    // 为所有数字输入框添加实时验证
    const numberInputs = document.querySelectorAll('input[type="number"], input:not([type])');
    
    numberInputs.forEach(input => {
        // 失去焦点时验证
        input.addEventListener('blur', function() {
            validateInput(this.id);
        });
        
        // 输入时清除错误状态
        input.addEventListener('input', function() {
            if (this.classList.contains('is-invalid')) {
                removeError(this);
            }
        });
    });
}

// 页面加载完成后初始化实时验证
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', addRealTimeValidation);
} else {
    addRealTimeValidation();
}

// 计算器操作函数
function calculate(operation) {
    // 验证输入值
    const num1Valid = validateInput('num1', true, true);
    const num2Valid = validateInput('num2', true, true);
    
    if (!num1Valid || !num2Valid) {
        return false;
    }
    
    // 获取输入值
    const num1 = parseFloat(document.getElementById('num1').value);
    const num2 = parseFloat(document.getElementById('num2').value);
    
    let result;
    
    // 执行相应的运算
    switch(operation) {
        case 'add':
            result = num1 + num2;
            break;
        case 'subtract':
            result = num1 - num2;
            break;
        case 'multiply':
            result = num1 * num2;
            break;
        case 'divide':
            if (num2 === 0) {
                alert('除数不能为零');
                return false;
            }
            result = num1 / num2;
            break;
        case 'power':
            result = Math.pow(num1, num2);
            break;
        default:
            alert('未知的操作');
            return false;
    }
    
    // 显示结果
    document.getElementById('result').value = result;
    
    return true;
}

// 单位转换函数
function convertUnits() {
    // 验证输入值
    const valueValid = validateInput('convertValue', true, true);
    
    if (!valueValid) {
        return false;
    }
    
    // 获取输入值和单位
    const value = parseFloat(document.getElementById('convertValue').value);
    const fromUnit = document.getElementById('fromUnit').value;
    const toUnit = document.getElementById('toUnit').value;
    const conversionType = document.getElementById('conversionType').value;
    
    let result;
    
    // 根据转换类型执行相应的转换
    switch(conversionType) {
        case 'length':
            result = convertLength(value, fromUnit, toUnit);
            break;
        case 'temperature':
            result = convertTemperature(value, fromUnit, toUnit);
            break;
        case 'weight':
            result = convertWeight(value, fromUnit, toUnit);
            break;
        case 'volume':
            result = convertVolume(value, fromUnit, toUnit);
            break;
        default:
            alert('未知的转换类型');
            return false;
    }
    
    // 显示结果
    document.getElementById('convertedResult').value = result;
    
    return true;
}

// 长度单位转换
function convertLength(value, fromUnit, toUnit) {
    // 先转换为米作为中间单位
    let meters;
    switch(fromUnit) {
        case 'meter':
            meters = value;
            break;
        case 'kilometer':
            meters = value * 1000;
            break;
        case 'mile':
            meters = value * 1609.34;
            break;
        default:
            return value;
    }
    
    // 从米转换为目标单位
    switch(toUnit) {
        case 'meter':
            return meters;
            break;
        case 'kilometer':
            return meters / 1000;
            break;
        case 'mile':
            return meters / 1609.34;
            break;
        default:
            return meters;
    }
}

// 温度单位转换
function convertTemperature(value, fromUnit, toUnit) {
    if (fromUnit === toUnit) return value;
    
    let celsius;
    
    // 先转换为摄氏度
    if (fromUnit === 'celsius') {
        celsius = value;
    } else if (fromUnit === 'fahrenheit') {
        celsius = (value - 32) * 5/9;
    }
    
    // 从摄氏度转换为目标单位
    if (toUnit === 'celsius') {
        return celsius;
    } else if (toUnit === 'fahrenheit') {
        return (celsius * 9/5) + 32;
    }
}

// 重量单位转换
function convertWeight(value, fromUnit, toUnit) {
    // 先转换为克作为中间单位
    let grams;
    switch(fromUnit) {
        case 'gram':
            grams = value;
            break;
        case 'kilogram':
            grams = value * 1000;
            break;
        case 'pound':
            grams = value * 453.592;
            break;
        default:
            return value;
    }
    
    // 从克转换为目标单位
    switch(toUnit) {
        case 'gram':
            return grams;
            break;
        case 'kilogram':
            return grams / 1000;
            break;
        case 'pound':
            return grams / 453.592;
            break;
        default:
            return grams;
    }
}

// 体积单位转换
function convertVolume(value, fromUnit, toUnit) {
    // 先转换为升作为中间单位
    let liters;
    switch(fromUnit) {
        case 'liter':
            liters = value;
            break;
        case 'milliliter':
            liters = value / 1000;
            break;
        case 'gallon':
            liters = value * 3.78541;
            break;
        default:
            return value;
    }
    
    // 从升转换为目标单位
    switch(toUnit) {
        case 'liter':
            return liters;
            break;
        case 'milliliter':
            return liters * 1000;
            break;
        case 'gallon':
            return liters / 3.78541;
            break;
        default:
            return liters;
    }
}