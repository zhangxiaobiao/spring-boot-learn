package com.imooc.mall.service.impl;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.google.zxing.WriterException;
import com.imooc.mall.common.Constant;
import com.imooc.mall.exception.ImoocMallException;
import com.imooc.mall.exception.ImoocMallExceptionEnum;
import com.imooc.mall.filter.UserFilter;
import com.imooc.mall.model.dao.CartMapper;
import com.imooc.mall.model.dao.OrderItemMapper;
import com.imooc.mall.model.dao.OrderMapper;
import com.imooc.mall.model.dao.ProductMapper;
import com.imooc.mall.model.pojo.Order;
import com.imooc.mall.model.pojo.OrderItem;
import com.imooc.mall.model.pojo.Product;
import com.imooc.mall.model.request.CreateOrderReq;
import com.imooc.mall.model.vo.CartVO;
import com.imooc.mall.model.vo.OrderItemVO;
import com.imooc.mall.model.vo.OrderVO;
import com.imooc.mall.service.CartService;
import com.imooc.mall.service.OrderService;
import com.imooc.mall.service.UserService;
import com.imooc.mall.utils.OrderCodeFactory;
import com.imooc.mall.utils.QRCodeGenerator;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    CartService cartService;

    @Autowired
    ProductMapper productMapper;

    @Autowired
    CartMapper cartMapper;

    @Autowired
    OrderMapper orderMapper;

    @Autowired
    OrderItemMapper orderItemMapper;

    @Autowired
    UserService userService;

    @Value("${file.upload.ip}")
    String ip;

    //???????????????
    @Transactional(rollbackFor = Exception.class)
    @Override
    public String create(CreateOrderReq createOrderReq){
        //????????????ID
        Integer userId = UserFilter.currentUser.getId();
        //???????????????????????????????????????
        List<CartVO> cartVOList = cartService.list(userId);
        ArrayList<CartVO> cartVOListTemp = new ArrayList<>();
        for (int i = 0; i < cartVOList.size(); i++) {
            CartVO cartVO =  cartVOList.get(i);
            if (cartVO.getSelected().equals(Constant.Cart.CHECKED)){
                cartVOListTemp.add(cartVO);
            }
        }
        cartVOList = cartVOListTemp;
        //??????????????????????????????????????????
        if (CollectionUtils.isEmpty(cartVOList)){
            throw new ImoocMallException(ImoocMallExceptionEnum.CART_EMPTY);
        }
        //???????????????????????????????????????????????????
        validSaleStatusAndStock(cartVOList);
        //??????????????????????????????item??????
        List<OrderItem> orderItemList = cartVOListToOrderItemList(cartVOList);

        //?????????
        for (int i = 0; i < orderItemList.size(); i++) {
            OrderItem orderItem = orderItemList.get(i);
            Product product = productMapper.selectByPrimaryKey(orderItem.getProductId());
            int stock = product.getStock() - orderItem.getQuantity();
            if (stock < 0){
                throw new ImoocMallException(ImoocMallExceptionEnum.NOT_ENOUGH);
            }
            product.setStock(stock);
            productMapper.updateByPrimaryKeySelective(product);
        }

        //???????????????????????????????????????
        cleanCart(cartVOList);
        //????????????
        Order order = new Order();
        //????????????????????????????????????
        String orderNo = OrderCodeFactory.getOrderCode(Long.valueOf(userId));
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setTotalPrice(totalPrice(orderItemList));
        order.setReceiverName(createOrderReq.getReceiverName());
        order.setReceiverMobile(createOrderReq.getReceiverMobile());
        order.setReceiverAddress(createOrderReq.getReceiverAddress());
        order.setOrderStatus(Constant.OrderStatusEnum.NOT_PAID.getCode());
        order.setPostage(0);
        order.setPaymentType(1);
        //?????????order??????
        orderMapper.insertSelective(order);
        //???????????????????????????order_item???
        for (int i = 0; i < orderItemList.size(); i++) {
            OrderItem orderItem =  orderItemList.get(i);
            orderItem.setOrderNo(order.getOrderNo());
            orderItemMapper.insertSelective(orderItem);
        }
        //???????????????
        return orderNo;
    }

    private Integer totalPrice(List<OrderItem> orderItemList) {
        Integer totalPrice = 0;
        for (int i = 0; i < orderItemList.size(); i++) {
            OrderItem orderItem = orderItemList.get(i);
            totalPrice += orderItem.getTotalPrice();
        }
        return totalPrice;
    }

    private void cleanCart(List<CartVO> cartVOList) {
        for (int i = 0; i < cartVOList.size(); i++) {
            CartVO cartVO = cartVOList.get(i);
            cartMapper.deleteByPrimaryKey(cartVO.getId());
        }
    }

    private List<OrderItem> cartVOListToOrderItemList(List<CartVO> cartVOList) {
        List<OrderItem> orderItemList = new ArrayList<>();
        for (int i = 0; i < cartVOList.size(); i++) {
            CartVO cartVO = cartVOList.get(i);
            OrderItem orderItem = new OrderItem();
            orderItem.setProductId(cartVO.getProductId());
            //????????????????????????
            orderItem.setProductName(cartVO.getProductName());
            orderItem.setProductImg(cartVO.getProductImage());
            orderItem.setUnitPrice(cartVO.getPrice());
            orderItem.setQuantity(cartVO.getQuantity());
            orderItem.setTotalPrice(cartVO.getTotalPrice());
            orderItemList.add(orderItem);
        }
        return orderItemList;
    }

    private void validSaleStatusAndStock(List<CartVO> cartVOList) {
        for (int i = 0; i < cartVOList.size(); i++) {
            CartVO cartVO =  cartVOList.get(i);
            Product product = productMapper.selectByPrimaryKey(cartVO.getProductId());
            //?????????????????????????????????????????????
            if (product == null || product.getStatus().equals(Constant.SaleStatus.NOT_SALE)){
                throw new ImoocMallException(ImoocMallExceptionEnum.NOT_SALE);
            }
            //??????????????????
            if (cartVO.getQuantity() > product.getStock()){
                throw new ImoocMallException(ImoocMallExceptionEnum.NOT_ENOUGH);
            }
        }
    }

    @Override
    public OrderVO detail(String orderNo){
        Order order = orderMapper.selectByOrderNo(orderNo);
        //???????????????????????????
        if (order == null){
            throw new ImoocMallException(ImoocMallExceptionEnum.NO_ORDER);
        }
        //????????????????????????????????????
        Integer userId = UserFilter.currentUser.getId();
        if (!order.getUserId().equals(userId)){
            throw new ImoocMallException(ImoocMallExceptionEnum.NOT_YOUR_ORDER);
        }
        OrderVO orderVO = getOrderVO(order);
        return orderVO;
    }

    private OrderVO getOrderVO(Order order) {
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order,orderVO);
        List<OrderItem> orderItemList = orderItemMapper.selectByOrderNo(order.getOrderNo());
        List<OrderItemVO> orderItemVOList = new ArrayList<>();
        for (int i = 0; i < orderItemList.size(); i++) {
            OrderItem orderItem = orderItemList.get(i);
            OrderItemVO orderItemVO = new OrderItemVO();
            BeanUtils.copyProperties(orderItem,orderItemVO);
            orderItemVOList.add(orderItemVO);
        }
        orderVO.setOrderItemVOList(orderItemVOList);
        orderVO.setOrderStatusName(Constant.OrderStatusEnum.codeOf(orderVO.getOrderStatus()).getValue());
        return orderVO;
    }

    @Override
    public PageInfo listForCustomer(Integer pageNum, Integer pageSize){
        Integer userId = UserFilter.currentUser.getId();
        PageHelper.startPage(pageNum,pageSize);
        List<Order> orderList = orderMapper.selectForCustomer(userId);
        List<OrderVO> orderVOList = orderListToOrderVOList(orderList);
        PageInfo orderPageInfo = new PageInfo<>(orderList);
        orderPageInfo.setList(orderVOList);
        return orderPageInfo;

    }

    private List<OrderVO> orderListToOrderVOList(List<Order> orderList) {
        List<OrderVO> orderVOSList = new ArrayList<>();
        for (int i = 0; i < orderList.size(); i++) {
            Order order =  orderList.get(i);
            OrderVO orderVO = getOrderVO(order);
            orderVOSList.add(orderVO);
        }
        return orderVOSList;
    }

    @Override
    public void cancel(String orderNo){
        Order order = orderMapper.selectByOrderNo(orderNo);
        //????????????????????????
        if (order == null) {
            throw new ImoocMallException(ImoocMallExceptionEnum.NO_ORDER);
        }
        //??????????????????
        Integer userId = UserFilter.currentUser.getId();
        if (!order.getUserId().equals(userId)){
            throw new ImoocMallException(ImoocMallExceptionEnum.NOT_YOUR_ORDER);
        }
        if (order.getOrderStatus() == Constant.OrderStatusEnum.NOT_PAID.getCode()){
            order.setOrderStatus(Constant.OrderStatusEnum.CANCELED.getCode());
            order.setEndTime(new Date());
            orderMapper.updateByPrimaryKeySelective(order);
        } else {
            throw new ImoocMallException(ImoocMallExceptionEnum.WRONG_ORDER_STATUS);
        }
    }

    @Override
    public String qrcode(String orderNo){
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        String address = ip +":"+ request.getLocalPort();
        String payUrl = "http://"+address+"/pay?orderNo="+orderNo;
        try {
            QRCodeGenerator.generateQRCodeImage(payUrl,350,350,Constant.FILE_UPLOAD_DIR+orderNo+".png");
        } catch (WriterException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        String pngAddress = "http://"+address+"/images/"+orderNo+".png";
        return pngAddress;
    }



    @Override
    public PageInfo listForAdmin(Integer pageNum, Integer pageSize){
        PageHelper.startPage(pageNum,pageSize);
        List<Order> orderList = orderMapper.selectAllForAdmin();
        List<OrderVO> orderVOList = orderListToOrderVOList(orderList);
        PageInfo orderPageInfo = new PageInfo<>(orderList);
        orderPageInfo.setList(orderVOList);
        return orderPageInfo;
    }

    @Override
    public void pay(String orderNo){
        Order order = orderMapper.selectByOrderNo(orderNo);
        //????????????????????????
        if (order == null) {
            throw new ImoocMallException(ImoocMallExceptionEnum.NO_ORDER);
        }
        if (order.getOrderStatus() == Constant.OrderStatusEnum.NOT_PAID.getCode()){
            order.setOrderStatus(Constant.OrderStatusEnum.PAID.getCode());
            order.setPayTime(new Date());
            orderMapper.updateByPrimaryKeySelective(order);
        } else {
            throw new ImoocMallException(ImoocMallExceptionEnum.WRONG_ORDER_STATUS);
        }
    }

    @Override
    public void deliver(String orderNo){
        Order order = orderMapper.selectByOrderNo(orderNo);
        //????????????????????????
        if (order == null) {
            throw new ImoocMallException(ImoocMallExceptionEnum.NO_ORDER);
        }
        if (order.getOrderStatus() == Constant.OrderStatusEnum.PAID.getCode()){
            order.setOrderStatus(Constant.OrderStatusEnum.DELIVERED.getCode());
            order.setDeliveryTime(new Date());
            orderMapper.updateByPrimaryKeySelective(order);
        } else {
            throw new ImoocMallException(ImoocMallExceptionEnum.WRONG_ORDER_STATUS);
        }
    }

    @Override
    public void finish(String orderNo) {
        Order order = orderMapper.selectByOrderNo(orderNo);
        //????????????????????????
        if (order == null) {
            throw new ImoocMallException(ImoocMallExceptionEnum.NO_ORDER);
        }
        //??????????????????????????????????????????
        if (!userService.checkAdminRole(UserFilter.currentUser) && !order.getUserId().equals(UserFilter.currentUser.getId())) {
            throw new ImoocMallException(ImoocMallExceptionEnum.NOT_YOUR_ORDER);
        }
        //???????????????????????????
        if (order.getOrderStatus() == Constant.OrderStatusEnum.DELIVERED.getCode()){
            order.setOrderStatus(Constant.OrderStatusEnum.FINISHED.getCode());
            order.setEndTime(new Date());
            orderMapper.updateByPrimaryKeySelective(order);
        } else {
            throw new ImoocMallException(ImoocMallExceptionEnum.WRONG_ORDER_STATUS);
        }
    }
}
